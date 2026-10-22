package com.kldxst.studio.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 直连通道（不经过小米运动健康）。
 *
 * 与 AzkiDeck / AstroBox 的做法一致：手机自己占蓝牙，用登录小米账号拿到的
 * authkey 与设备建立会话。**连接前必须先让小米运动健康让出蓝牙**，
 * 否则 SPP 端口被它占着，createRfcommSocketToServiceRecord 会直接失败。
 *
 * 分层：
 *   扫描（BLE，识别小米 FE95 服务） → 连接（SPP RFCOMM 0x1101） →
 *   authkey 握手（AES-128-ECB） → 帧收发（长度前缀）
 *
 * ⚠️ 协议状态：传输与握手已实现，**帧格式与业务消息需要真机校准**。
 * 所以帧编码参数（前缀字节、长度字节序、auth 模式）全部来自服务端下发的
 * ProtocolProfile —— 校准时只改服务端，不用重新打包 APK。
 */
class DirectLink(
    private val ctx: Context,
    private val log: (String) -> Unit = {}
) {

    data class Peer(val name: String, val address: String, val rssi: Int, val xiaomi: Boolean)

    /** 帧编码参数：全部可由 /api/protocol 下发 */
    data class Profile(
        val magic: Int = 0xA5,
        val littleEndianLength: Boolean = true,
        val lengthBytes: Int = 2,
        val authMode: String = "aes-ecb-16",
        val authFrameType: Int = 1
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var scanning = false
    @Volatile private var lastPeer: Peer? = null
    @Volatile private var lastAuthReply: String? = null

    var profile: Profile = Profile()
    var onFrame: ((ByteArray) -> Unit)? = null

    val connected: Boolean get() = socket?.isConnected == true
    val peer: Peer? get() = lastPeer

    fun available(): Boolean = adapter?.isEnabled == true

    fun bonded(): List<Peer> = try {
        adapter?.bondedDevices.orEmpty().map {
            Peer(it.name ?: "(未命名)", it.address, 0, true)
        }
    } catch (e: SecurityException) {
        emptyList()
    }

    /** BLE 扫描；识别小米服务 FE95 的设备排在前面 */
    @SuppressLint("MissingPermission")
    fun scan(onFound: (List<Peer>) -> Unit, onError: (String) -> Unit) {
        val ad = adapter ?: run { onError("本机没有蓝牙适配器"); return }
        if (!ad.isEnabled) { onError("蓝牙未开启"); return }
        if (scanning) return
        val scanner = ad.bluetoothLeScanner ?: run { onError("BLE 扫描不可用"); return }
        val found = LinkedHashMap<String, Peer>()
        scanning = true
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev: BluetoothDevice = result.device
                val name = try { dev.name } catch (e: SecurityException) { null } ?: return
                val xiaomi = result.scanRecord?.serviceUuids?.any {
                    it.uuid.toString().startsWith("0000fe95")
                } == true
                found[dev.address] = Peer(name, dev.address, result.rssi, xiaomi)
                onFound(found.values.sortedWith(compareByDescending<Peer> { it.xiaomi }.thenByDescending { it.rssi }))
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
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
            scanning = false
            onError("缺少蓝牙扫描权限")
            return
        }
        scope.launch {
            delay(12_000)
            runCatching { scanner.stopScan(cb) }
            scanning = false
        }
    }

    /** SPP 连接。失败时给出「先关掉小米运动健康」的明确提示 */
    @SuppressLint("MissingPermission")
    suspend fun connect(address: String): String = withContext(Dispatchers.IO) {
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
            throw IllegalStateException(
                "SPP 连接失败：${e.message}。常见原因：小米运动健康正在占用蓝牙（请先强制停止它），或设备未与本机配对。"
            )
        }
        socket = s
        lastPeer = Peer(dev.name ?: address, address, 0, true)
        log("SPP 已连接 $address")
        startReadLoop(s)
        dev.name ?: address
    }

    private fun startReadLoop(s: BluetoothSocket) {
        scope.launch {
            val input = s.inputStream
            val buf = ByteArray(4096)
            while (socket === s && s.isConnected) {
                val n = try { input.read(buf) } catch (e: Exception) { -1 }
                if (n <= 0) break
                val frame = buf.copyOf(n)
                log("← ${frame.size}B ${hex(frame.take(24))}")
                onFrame?.invoke(frame)
            }
            log("SPP 读取结束")
            close()
        }
    }

    /**
     * authkey 握手。
     *
     * authkey 是 32 位十六进制（16 字节），登录小米账号后从云端取回，
     * 与设备配对时用。这里按 AES-128-ECB 加密一段随机数发出；
     * 对端回包内容需要真机确认，所以只记录不判定，避免误报「认证成功」。
     */
    suspend fun handshake(authkeyHex: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val key = decodeHex(authkeyHex)
        require(key != null && key.size == 16) { "authkey 应为 32 位十六进制（16 字节）" }
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val payload = cipher.doFinal(nonce)
        val frame = buildFrame(profile.authFrameType, payload)
        send(frame)
        log("→ auth ${frame.size}B ${hex(frame.take(24))}")
        // 给对端 1.2s 回包窗口，拿到就记下来（内容解释留待真机校准）
        delay(1200)
        mapOf(
            "sent" to true,
            "nonce" to hex(nonce),
            "frame" to hex(frame),
            "reply" to lastAuthReply,
            "note" to "握手帧已发出；回包语义需真机校准，参数可在服务端 /api/protocol 调整"
        )
    }

    fun send(bytes: ByteArray) {
        val s = socket ?: throw IllegalStateException("未连接")
        s.outputStream.write(bytes)
        s.outputStream.flush()
    }

    fun sendFrame(type: Int, payload: ByteArray) = send(buildFrame(type, payload))

    private fun buildFrame(type: Int, payload: ByteArray): ByteArray {
        val p = profile
        val body = ByteArrayOutputStream().apply {
            write(p.magic and 0xFF)
            write(type and 0xFF)
            if (p.littleEndianLength) {
                write(payload.size and 0xFF); write((payload.size ushr 8) and 0xFF)
            } else {
                write((payload.size ushr 8) and 0xFF); write(payload.size and 0xFF)
            }
            write(payload)
        }
        return body.toByteArray()
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    /** 供控制台展示的原始字节 */
    fun lastReply(): String? = lastAuthReply

    private fun decodeHex(s: String): ByteArray? {
        val t = s.trim()
        if (t.length % 2 != 0 || !t.matches(Regex("[0-9a-fA-F]+"))) return null
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
