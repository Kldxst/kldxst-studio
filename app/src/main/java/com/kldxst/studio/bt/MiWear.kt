package com.kldxst.studio.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 直连通道：手机自己占蓝牙，不经小米运动健康。
 *
 * 两条链路：
 *   SPP  —— RFCOMM 0x1101，带宽稳定，装包/传文件优先走它
 *   BLE  —— 服务 0xFE95，功耗低，通知/状态/网桥走它
 *
 * 帧格式、消息号、GATT 特征都由 [ProtocolProfile] 决定，可从服务端热更新：
 * 这套协议没有公开文档，真机校准一次后所有人拿到正确参数，不必重装应用。
 *
 * 使用前必须让小米运动健康让出蓝牙，否则 SPP 端口被占、GATT 连上也不通。
 */
class MiWear(
    private val ctx: Context,
    private val log: (String) -> Unit = {}
) {

    data class Peer(
        val name: String,
        val address: String,
        val rssi: Int,
        val xiaomi: Boolean,
        val productId: Int? = null
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    var profile: ProtocolProfile = ProtocolProfile()

    /* ---- SPP ---- */
    @Volatile private var socket: BluetoothSocket? = null
    private var spool = ByteArrayOutputStream()

    /* ---- BLE ---- */
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var txChar: BluetoothGattCharacteristic? = null
    private var gattReady: CompletableDeferred<Boolean>? = null
    private var rxBuffer = ByteArrayOutputStream()

    @Volatile var peer: Peer? = null
        private set
    @Volatile var transport: String = "none"
        private set
    @Volatile var authenticated: Boolean = false
        private set
    @Volatile var lastAuthReply: String? = null
        private set

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Frame>>()
    private val frames = ArrayDeque<Frame>()
    private val frameLock = Any()

    var onFrame: ((Frame) -> Unit)? = null

    val connected: Boolean
        get() = socket?.isConnected == true || transport == "ble"

    fun available(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun bonded(): List<Peer> = try {
        adapter?.bondedDevices.orEmpty().map { Peer(it.name ?: "(未命名)", it.address, 0, true) }
    } catch (e: SecurityException) {
        emptyList()
    }

    /* ------------------------------------------------------------------ */
    /*  扫描                                                              */
    /* ------------------------------------------------------------------ */

    @SuppressLint("MissingPermission")
    fun scan(onFound: (List<Peer>) -> Unit, onError: (String) -> Unit, durationMs: Long = 10_000) {
        val ad = adapter ?: return onError("本机没有蓝牙适配器")
        if (!ad.isEnabled) return onError("蓝牙未开启")
        val scanner = ad.bluetoothLeScanner ?: return onError("BLE 扫描不可用")
        val found = LinkedHashMap<String, Peer>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val name = try { dev.name } catch (e: SecurityException) { null } ?: return
                val record = result.scanRecord
                val uuids = record?.serviceUuids.orEmpty().map { it.uuid.toString().lowercase() }
                val xiaomi = uuids.any { it.startsWith("0000fe95") || it.startsWith("0000fd") }
                val pid = record?.serviceData?.entries
                    ?.firstOrNull { it.key.uuid.toString().lowercase().startsWith("0000fe95") }
                    ?.value?.let { parseMiProductId(it) }
                found[dev.address] = Peer(name, dev.address, result.rssi, xiaomi, pid)
                onFound(
                    found.values.sortedWith(
                        compareByDescending<Peer> { it.xiaomi }.thenByDescending { it.rssi }
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                onError("扫描失败 errorCode=$errorCode")
            }
        }
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb
            )
        } catch (e: SecurityException) {
            return onError("缺少蓝牙扫描权限")
        }
        scope.launch {
            delay(durationMs)
            runCatching { scanner.stopScan(cb) }
        }
    }

    /** 小米 MiBeacon 广播里取产品号（帧控 2 + 产品号 2 + 序号 1 …） */
    private fun parseMiProductId(data: ByteArray): Int? {
        if (data.size < 5) return null
        val frameControl = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val hasMac = frameControl and 0x10 != 0
        var off = 2
        if (data.size < off + 2) return null
        val pid = (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
        off += 2
        if (hasMac) off += 6
        return if (pid in 1..65535) pid else null
    }

    /* ------------------------------------------------------------------ */
    /*  SPP 连接                                                          */
    /* ------------------------------------------------------------------ */

    @SuppressLint("MissingPermission")
    suspend fun connectSpp(address: String): String = withContext(Dispatchers.IO) {
        val ad = adapter ?: throw IllegalStateException("本机没有蓝牙适配器")
        if (!ad.isEnabled) throw IllegalStateException("蓝牙未开启")
        close()
        val dev = ad.getRemoteDevice(address)
        runCatching { ad.cancelDiscovery() }
        val s = try {
            dev.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: Exception) {
            throw IllegalStateException("无法创建 SPP 通道：${e.message}")
        }
        try {
            s.connect()
        } catch (e: Exception) {
            runCatching { s.close() }
            throw IllegalStateException("SPP 连接失败：${e.message}。先强制停止小米运动健康再试")
        }
        socket = s
        spool = ByteArrayOutputStream()
        transport = "spp"
        peer = Peer(dev.name ?: address, address, 0, true)
        log("SPP 已连接 $address")
        startSppRead(s)
        dev.name ?: address
    }

    private fun startSppRead(s: BluetoothSocket) {
        scope.launch {
            val input = s.inputStream
            val buf = ByteArray(4096)
            while (socket === s && s.isConnected) {
                val n = try { input.read(buf) } catch (e: Exception) { -1 }
                if (n <= 0) break
                spool.write(buf, 0, n)
                drain(spool)
            }
            log("SPP 读取结束")
            close()
        }
    }

    /* ------------------------------------------------------------------ */
    /*  BLE 连接                                                          */
    /* ------------------------------------------------------------------ */

    @SuppressLint("MissingPermission")
    suspend fun connectGatt(address: String): String = withContext(Dispatchers.IO) {
        val ad = adapter ?: throw IllegalStateException("本机没有蓝牙适配器")
        if (!ad.isEnabled) throw IllegalStateException("蓝牙未开启")
        close()
        val dev = ad.getRemoteDevice(address)
        val ready = CompletableDeferred<Boolean>()
        gattReady = ready
        rxBuffer = ByteArrayOutputStream()
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("GATT 已连接，开始发现服务")
                    g.discoverServices()
                } else {
                    log("GATT 断开 status=$status")
                    if (!ready.isCompleted) ready.complete(false)
                    close()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.getService(UUID.fromString(SERVICE_FE95))
                    ?: g.services.firstOrNull { it.uuid.toString().lowercase().startsWith("0000fe95") }
                if (service == null) {
                    log("未找到 FE95 服务；服务列表=" + g.services.joinToString { it.uuid.toString() })
                    ready.complete(false)
                    return
                }
                val chars = service.characteristics
                // 优先取带写+通知的特征；小米常见布局：一写一通知
                val writable = chars.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }
                val notifiable = chars.firstOrNull {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                }
                if (writable == null || notifiable == null) {
                    log("FE95 特征不全：写=${writable?.uuid} 通知=${notifiable?.uuid}")
                    ready.complete(false)
                    return
                }
                txChar = writable
                g.setCharacteristicNotification(notifiable, true)
                val cccd = notifiable.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(cccd)
                        }
                    }
                }
                log("FE95 就绪 写=${writable.uuid} 通知=${notifiable.uuid}")
                ready.complete(true)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                rxBuffer.write(value, 0, value.size)
                drain(rxBuffer)
            }

            @Deprecated("兼容 API 32 以下")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val v = c.value ?: return
                rxBuffer.write(v, 0, v.size)
                drain(rxBuffer)
            }
        }
        val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        } else {
            dev.connectGatt(ctx, false, cb)
        }
        gatt = g
        val ok = withTimeoutOrNull(12_000) { ready.await() } == true
        if (!ok) {
            close()
            throw IllegalStateException("BLE 连接失败：未找到可用的 FE95 通道")
        }
        transport = "ble"
        peer = Peer(dev.name ?: address, address, 0, true)
        dev.name ?: address
    }

    /* ------------------------------------------------------------------ */
    /*  收发                                                              */
    /* ------------------------------------------------------------------ */

    private fun drain(stream: ByteArrayOutputStream) {
        val buf = stream.toByteArray()
        val (list, rest) = FrameCodec.decode(profile, buf)
        stream.reset()
        if (rest.isNotEmpty()) stream.write(rest)
        for (f in list) {
            log("← type=${f.type} len=${f.payload.size} ${FrameCodec.hex(f.payload.copyOfRange(0, minOf(16, f.payload.size)))}")
            if (f.type == profile.authType) {
                lastAuthReply = FrameCodec.hex(f.payload)
                authenticated = true
            }
            pending[f.type]?.let { if (!it.isCompleted) it.complete(f) }
            synchronized(frameLock) { frames.addLast(f) }
            onFrame?.invoke(f)
        }
    }

    @SuppressLint("MissingPermission")
    fun send(bytes: ByteArray) {
        when (transport) {
            "spp" -> {
                val s = socket ?: throw IllegalStateException("未连接")
                s.outputStream.write(bytes)
                s.outputStream.flush()
            }
            "ble" -> {
                val g = gatt ?: throw IllegalStateException("未连接")
                val c = txChar ?: throw IllegalStateException("未找到写特征")
                // 超过 MTU 时按 180 字节切片，保证不丢包
                var off = 0
                while (off < bytes.size) {
                    val end = minOf(off + 180, bytes.size)
                    val slice = bytes.copyOfRange(off, end)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(c, slice, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            c.value = slice
                            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            g.writeCharacteristic(c)
                        }
                    }
                    off = end
                }
            }
            else -> throw IllegalStateException("未连接")
        }
    }

    fun sendFrame(type: Int, payload: ByteArray) {
        val bytes = FrameCodec.encode(profile, type, payload)
        log("→ type=$type len=${payload.size} ${FrameCodec.hex(bytes.copyOfRange(0, minOf(16, bytes.size)))}")
        send(bytes)
    }

    /** 发一帧并等指定类型回包；超时返回 null（界面据此提示，而不是一直转圈） */
    suspend fun request(type: Int, payload: ByteArray, expect: Int = type, timeoutMs: Long = 4000): Frame? {
        val slot = CompletableDeferred<Frame>()
        pending[expect] = slot
        sendFrame(type, payload)
        return try {
            withTimeoutOrNull(timeoutMs) { slot.await() }
        } finally {
            pending.remove(expect)
        }
    }

    /* ------------------------------------------------------------------ */
    /*  authkey 握手                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * authkey 是 32 位十六进制（16 字节），登录小米账号后取回。
     * 这里按 AES-128-ECB 加密一段随机数发出；回包内容原样记录，
     * 语义需真机确认，所以不谎报「认证成功」——只报「已发出、收到 N 字节」。
     */
    suspend fun auth(authkeyHex: String): JSONObject = withContext(Dispatchers.IO) {
        val key = FrameCodec.unhex(authkeyHex)
        require(key != null && key.size == 16) { "authkey 应为 32 位十六进制" }
        // 与 AstroBox 的 miwear-auth 一致：HMAC-SHA256(authkey, 手机 nonce)
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        val hmac = mac.doFinal(nonce)
        val payload = nonce + hmac
        authenticated = false
        lastAuthReply = null
        val reply = request(profile.authType, payload, profile.authType, 3000)
        JSONObject()
            .put("sent", true)
            .put("nonce", FrameCodec.hex(nonce))
            .put("hmac", FrameCodec.hex(hmac))
            .put("reply", lastAuthReply ?: JSONObject.NULL)
            .put("replied", reply != null)
            .put("note", if (reply == null) "已发出，未收到回包" else "已收到回包")
    }

    /* ------------------------------------------------------------------ */
    /*  命令                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * 业务命令：消息号来自服务端 profile.ops，payload 由调用方给。
     * 未配置消息号时抛异常，让界面显示「待校准」而不是静默失败。
     */
    suspend fun command(op: String, payload: ByteArray, timeoutMs: Long = 6000): JSONObject {
        val type = profile.ops[op] ?: throw IllegalStateException("命令 $op 尚未配置消息号（待校准）")
        val reply = request(type, payload, type, timeoutMs)
        return JSONObject()
            .put("op", op)
            .put("type", type)
            .put("sent", payload.size)
            .put("replied", reply != null)
            .put("reply", reply?.let { FrameCodec.hex(it.payload) } ?: JSONObject.NULL)
    }

    /** 已配置的命令清单，供界面判断哪些按钮可用 */
    fun ops(): Map<String, Int> = profile.ops

    fun recent(count: Int): List<JSONObject> = synchronized(frameLock) {
        frames.toList().takeLast(count).map {
            JSONObject()
                .put("type", it.type)
                .put("len", it.payload.size)
                .put("hex", FrameCodec.hex(it.payload.copyOfRange(0, minOf(48, it.payload.size))))
        }
    }

    fun close() {
        authenticated = false
        runCatching { socket?.close() }
        socket = null
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        txChar = null
        transport = "none"
        peer = null
        spool = ByteArrayOutputStream()
        rxBuffer = ByteArrayOutputStream()
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_FE95 = "0000fe95-0000-1000-8000-00805f9b34fb"
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
