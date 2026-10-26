package com.kldxst.studio.wear

import com.google.protobuf.ByteString
import com.kldxst.studio.bt.MiWear
import com.kldxst.studio.proto.Account
import com.kldxst.studio.proto.Auth
import com.kldxst.studio.proto.CompanionDevice
import com.kldxst.studio.proto.WearPacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * L1 SAR + L2 + WearPacket 的会话层。
 *
 * 这不是“每条消息一个 byte 命令号”的简化实现：
 *   L1 8B 头/CRC/seq/ACK → L2 channel/opcode → protobuf WearPacket(type,id,payload)。
 * 使用保守的 stop-and-wait SAR 窗口（一次一帧等 ACK），优先保证真实设备兼容性；
 * 大数据 MASS 也能用同一 L1 会话逐片发送。
 */
class MiWearSession(
    private val link: MiWear,
    private val log: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sendLock = Mutex()
    private val rxLock = Any()
    private var rxBuffer = ByteArrayOutputStream()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<WearPacket>>()
    private val listeners = CopyOnWriteArrayList<(WearPacket) -> Unit>()

    @Volatile private var txSeq = 0
    @Volatile private var rxSeq = 0
    @Volatile private var started = false
    @Volatile private var currentSeq = -1
    @Volatile private var lastOutbound: ByteArray? = null
    @Volatile private var ackDeferred: CompletableDeferred<Int>? = null
    @Volatile private var startDeferred: CompletableDeferred<Unit>? = null

    @Volatile var keys: AuthKeys? = null
        private set
    @Volatile var authenticated = false
        private set
    @Volatile var authState: String = "none"
        private set
    @Volatile var lastError: String? = null

    init {
        link.onRawData = { bytes -> feed(bytes) }
    }

    fun onPacket(listener: (WearPacket) -> Unit) {
        listeners.add(listener)
    }

    fun clearPacketListeners() {
        listeners.clear()
    }

    suspend fun open(authkeyHex: String) {
        reset()
        startSession()
        authenticate(authkeyHex)
    }

    suspend fun startSession() {
        reset()
        started = false
        startDeferred = CompletableDeferred()
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                sendCommand(START_REQ)
                val ok = withTimeoutOrNull(if (attempt == 2) 5_000 else 3_000) {
                    startDeferred?.await()
                } != null
                if (ok) {
                    started = true
                    log("L1 SAR 已启动")
                    return
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("L1 握手失败：${last?.message ?: "设备未回应 L1StartRsp"}")
    }

    suspend fun authenticate(authkeyHex: String) {
        val authkey = MiWearCrypto.hexToBytes(authkeyHex)
            ?: throw IllegalArgumentException("authkey 应为 32 位十六进制")
        authState = "auth_verify"
        val phoneNonce = MiWearCrypto.randomBytes(16)
        val verify = Auth.AppVerify.newBuilder()
            .setAppRandom(ByteString.copyFrom(phoneNonce))
            .build()
        val p = WearPacket.newBuilder()
            .setType(WearPacket.Type.ACCOUNT)
            .setId(Account.AccountID.AUTH_VERIFY.number)
            .setAccount(Account.newBuilder().setAuthAppVerify(verify))
            .build()

        val reply = request(p, timeoutMs = 10_000, encrypted = false)
        val verifyReply = reply.account?.takeIf { it.hasAuthDeviceVerify() }?.authDeviceVerify
            ?: throw IllegalStateException("设备未返回 DeviceVerify")
        val watchNonce = verifyReply.deviceRandom.toByteArray()
        val watchSign = verifyReply.deviceSign.toByteArray()
        if (watchNonce.size != 16 || watchSign.size != 32) {
            throw IllegalStateException("DeviceVerify nonce/hmac 长度不匹配")
        }

        val k = MiWearCrypto.kdf(authkey, phoneNonce, watchNonce)
        val expected = MiWearCrypto.hmacSha256(k.decKey, watchNonce, phoneNonce)
        if (!MiWearCrypto.constantTimeEquals(expected, watchSign)) {
            authState = "auth_failed"
            throw IllegalStateException("authkey 与设备不匹配")
        }

        val appSign = MiWearCrypto.hmacSha256(k.encKey, phoneNonce, watchNonce)
        val companion = CompanionDevice.newBuilder()
            .setDeviceType(CompanionDevice.DeviceType.ANDROID)
            .setDeviceName("KdSync")
            .setAppCapability(-1)
            .build()
        val ccmNonce = k.encNonce + ByteArray(8)
        val encryptedCompanion = MiWearCrypto.aes128Ccm(
            k.encKey, ccmNonce, ByteArray(0), companion.toByteArray()
        )
        val confirm = Auth.AppConfirm.newBuilder()
            .setAppSign(ByteString.copyFrom(appSign))
            .setEncryptCompanionDevice(ByteString.copyFrom(encryptedCompanion))
            .build()
        val confirmPacket = WearPacket.newBuilder()
            .setType(WearPacket.Type.ACCOUNT)
            .setId(Account.AccountID.AUTH_CONFIRM.number)
            .setAccount(Account.newBuilder().setAuthAppConfirm(confirm))
            .build()

        // AstroBox 源码在 DeviceVerify 后把密钥写入组件，但 AppConfirm 本身仍走 plain L2。
        keys = k
        val confirmReply = request(confirmPacket, timeoutMs = 10_000, encrypted = false)
        val result = confirmReply.account
            ?.takeIf { it.hasAuthDeviceConfirm() }
            ?.authDeviceConfirm
            ?.confirmResult
            ?: throw IllegalStateException("设备未返回 DeviceConfirm")
        if (!result) {
            authState = "auth_failed"
            keys = null
            throw IllegalStateException("设备拒绝认证确认")
        }
        authenticated = true
        authState = "ready"
        log("authkey 认证完成")
    }

    suspend fun request(
        packet: WearPacket,
        timeoutMs: Long = 8_000,
        encrypted: Boolean = keys != null && authenticated
    ): WearPacket {
        val key = "${packet.type.number}:${packet.id}"
        val slot = CompletableDeferred<WearPacket>()
        val old = pending.putIfAbsent(key, slot)
        if (old != null) throw IllegalStateException("命令 $key 正在等待回包")
        try {
            sendPb(packet, encrypted)
            return withTimeoutOrNull(timeoutMs) { slot.await() }
                ?: throw IllegalStateException("等待设备回包超时（$key）")
        } finally {
            pending.remove(key, slot)
        }
    }

    suspend fun sendPb(packet: WearPacket, encrypted: Boolean = keys != null && authenticated) {
        val plain = packet.toByteArray()
        val body = if (encrypted) {
            val current = keys ?: throw IllegalStateException("尚未完成 authkey 认证")
            L2Codec.encode(
                L2Codec.CHANNEL_PB,
                L2Codec.OP_WRITE_ENC,
                MiWearCrypto.aes128Ctr(current.encKey, plain)
            )
        } else {
            L2Codec.encode(L2Codec.CHANNEL_PB, L2Codec.OP_WRITE, plain)
        }
        sendL1(body)
    }

    suspend fun sendFire(packet: WearPacket, encrypted: Boolean = keys != null && authenticated) {
        sendPb(packet, encrypted)
    }

    /** 直接发 L2 数据（MASS 分片走这里）。L1 ACK 由 sendL1 保证。 */
    suspend fun sendL2(channel: Int, opcode: Int, payload: ByteArray) {
        sendL1(L2Codec.encode(channel, opcode, payload))
    }

    /** 在发送大文件前先占位，避免设备在最后一个分片后立刻回的结果被丢掉。 */
    class PacketWait internal constructor(
        internal val key: String,
        internal val slot: CompletableDeferred<WearPacket>
    )

    fun preparePacket(type: Int, id: Int): PacketWait {
        val key = "$type:$id"
        val slot = CompletableDeferred<WearPacket>()
        val old = pending.putIfAbsent(key, slot)
        if (old != null) throw IllegalStateException("命令 $key 已在等待回包")
        return PacketWait(key, slot)
    }

    suspend fun awaitPacket(waiter: PacketWait, timeoutMs: Long = 120_000): WearPacket {
        return try {
            withTimeoutOrNull(timeoutMs) { waiter.slot.await() }
                ?: throw IllegalStateException("等待设备事件超时（${waiter.key}）")
        } finally {
            pending.remove(waiter.key, waiter.slot)
        }
    }

    suspend fun awaitPacket(type: Int, id: Int, timeoutMs: Long = 120_000): WearPacket {
        val waiter = preparePacket(type, id)
        return awaitPacket(waiter, timeoutMs)
    }

    fun cancelPacket(waiter: PacketWait) {
        pending.remove(waiter.key, waiter.slot)
    }

    private suspend fun sendL1(payload: ByteArray) {
        if (!started) throw IllegalStateException("L1 尚未启动")
        sendLock.withLock {
            val seq = txSeq
            txSeq = (txSeq + 1) and 0xFF
            currentSeq = seq
            val frame = MiWearFrameCodec.encode(
                MiWearFrameCodec.TYPE_DATA,
                false,
                seq,
                payload
            )
            lastOutbound = frame
            ackDeferred = CompletableDeferred()
            try {
                var attempts = 0
                while (true) {
                    link.send(frame)
                    val slot = ackDeferred
                    val ack = withTimeoutOrNull(if (link.transport == "ble") 8_000 else 10_000) {
                        slot?.await()
                    }
                    if (ack != null) return
                    if (++attempts >= 3) throw IllegalStateException("L1 数据帧 seq=$seq 未收到 ACK")
                    ackDeferred = CompletableDeferred()
                }
            } finally {
                currentSeq = -1
                lastOutbound = null
                ackDeferred = null
            }
        }
    }

    private suspend fun sendCommand(payload: ByteArray) {
        val frame = MiWearFrameCodec.encode(
            MiWearFrameCodec.TYPE_CMD,
            false,
            0,
            payload
        )
        // 与数据帧共用同一把发送锁，避免 SPP 上命令帧和数据帧交错。
        sendLock.withLock { link.send(frame) }
    }

    private fun feed(bytes: ByteArray) {
        val frames: List<L1Frame>
        synchronized(rxLock) {
            rxBuffer.write(bytes)
            val (list, rest) = MiWearFrameCodec.decode(rxBuffer.toByteArray())
            rxBuffer = ByteArrayOutputStream().also { if (rest.isNotEmpty()) it.write(rest) }
            frames = list
        }
        for (frame in frames) onFrame(frame)
    }

    private fun onFrame(frame: L1Frame) {
        when (frame.type) {
            MiWearFrameCodec.TYPE_ACK -> {
                val current = currentSeq
                // 兼容“确认当前帧”和“确认下一个期望序号”两种设备实现；
                // 忽略上一条数据迟到的 ACK，避免 stop-and-wait 下误推进。
                if (current >= 0 && (frame.seq == current || frame.seq == ((current + 1) and 0xFF))) {
                    ackDeferred?.let { if (!it.isCompleted) it.complete(frame.seq) }
                }
            }
            MiWearFrameCodec.TYPE_NAK -> {
                if (frame.seq == currentSeq) {
                    val retry = lastOutbound
                    if (retry != null) scope.launch { runCatching { link.send(retry) } }
                }
            }
            MiWearFrameCodec.TYPE_CMD -> onCommand(frame.payload)
            MiWearFrameCodec.TYPE_DATA -> {
                if (!frame.frx && frame.seq != rxSeq) {
                    scope.launch {
                        runCatching {
                            link.send(MiWearFrameCodec.encode(MiWearFrameCodec.TYPE_NAK, false, rxSeq, ByteArray(0)))
                        }
                    }
                    return
                }
                if (!frame.frx) {
                    rxSeq = (frame.seq + 1) and 0xFF
                    scope.launch {
                        runCatching {
                            link.send(MiWearFrameCodec.encode(MiWearFrameCodec.TYPE_ACK, false, frame.seq, ByteArray(0)))
                        }
                    }
                }
                onL2(frame.payload)
            }
        }
    }

    private fun onCommand(payload: ByteArray) {
        val command = L1Command.parse(payload) ?: return
        when (command.code) {
            L1Command.START_REQ -> {
                scope.launch {
                    runCatching { sendCommand(L1Command.startResponse()) }
                    started = true
                    startDeferred?.complete(Unit)
                }
            }
            L1Command.START_RSP -> {
                started = true
                startDeferred?.complete(Unit)
            }
            L1Command.STOP_REQ -> {
                started = false
                scope.launch { runCatching { sendCommand(L1Command.stopResponse()) } }
            }
            L1Command.STOP_RSP -> started = false
        }
    }

    private fun onL2(payload: ByteArray) {
        val l2 = try {
            L2Codec.decode(payload)
        } catch (e: Exception) {
            lastError = e.message
            log("L2 解析失败：${e.message}")
            return
        }
        if (l2.channel != L2Codec.CHANNEL_PB) return
        val plain = try {
            if (l2.opcode == L2Codec.OP_WRITE_ENC) {
                val k = keys ?: throw IllegalStateException("收到加密包但尚未派生密钥")
                MiWearCrypto.aes128Ctr(k.decKey, l2.payload)
            } else {
                l2.payload
            }
        } catch (e: Exception) {
            lastError = e.message
            log("L2 解密失败：${e.message}")
            return
        }
        val packet = try {
            WearPacket.parseFrom(plain)
        } catch (e: Exception) {
            lastError = e.message
            log("protobuf 解析失败：${e.message}")
            return
        }
        val key = "${packet.type.number}:${packet.id}"
        pending.remove(key)?.let { if (!it.isCompleted) it.complete(packet) }
        listeners.forEach { runCatching { it(packet) } }
    }

    fun close() {
        runCatching { link.onRawData = null }
        pending.values.forEach { if (!it.isCompleted) it.completeExceptionally(IllegalStateException("连接已断开")) }
        pending.clear()
        keys = null
        authenticated = false
        authState = "none"
        started = false
        synchronized(rxLock) { rxBuffer = ByteArrayOutputStream() }
        // 不 cancel scope：同一 WearSession 会被前台服务重连复用。
    }

    private fun reset() {
        keys = null
        authenticated = false
        authState = "none"
        started = false
        txSeq = 0
        rxSeq = 0
        currentSeq = -1
        lastOutbound = null
        ackDeferred = null
        startDeferred = null
        synchronized(rxLock) { rxBuffer = ByteArrayOutputStream() }
        pending.clear()
        link.onRawData = { bytes -> feed(bytes) }
    }

    private object L1Command {
        const val START_REQ = 1
        const val START_RSP = 2
        const val STOP_REQ = 3
        const val STOP_RSP = 4

        data class Parsed(val code: Int, val config: Map<Int, ByteArray>)

        fun parse(payload: ByteArray): Parsed? {
            if (payload.isEmpty()) return null
            val config = LinkedHashMap<Int, ByteArray>()
            var i = 1
            while (i + 3 <= payload.size) {
                val key = payload[i].toInt() and 0xFF
                val len = (payload[i + 1].toInt() and 0xFF) or
                    ((payload[i + 2].toInt() and 0xFF) shl 8)
                i += 3
                if (i + len > payload.size) break
                config[key] = payload.copyOfRange(i, i + len)
                i += len
            }
            return Parsed(payload[0].toInt() and 0xFF, config)
        }

        fun startRequest(): ByteArray = build(START_REQ, 4, 10_000)
        fun startResponse(): ByteArray = build(START_RSP, 4, 10_000)
        fun stopRequest(): ByteArray = build(STOP_REQ, 4, 10_000)
        fun stopResponse(): ByteArray = build(STOP_RSP, 4, 10_000)

        private fun build(code: Int, txWin: Int, timeoutMs: Int): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(code)
            fun field(key: Int, value: ByteArray) {
                out.write(key)
                out.write(value.size and 0xFF)
                out.write((value.size ushr 8) and 0xFF)
                out.write(value)
            }
            // L1CmdBuilder 的配置键，顺序不参与语义。
            field(1, byteArrayOf(1, 0, 0))       // version
            field(2, byteArrayOf(0x00, 0xFC.toByte())) // mps=64512 LE
            field(3, byteArrayOf((txWin and 0xFF).toByte(), ((txWin ushr 8) and 0xFF).toByte()))
            field(4, byteArrayOf((timeoutMs and 0xFF).toByte(), ((timeoutMs ushr 8) and 0xFF).toByte()))
            return out.toByteArray()
        }
    }

    companion object {
        private val START_REQ = L1Command.startRequest()
    }
}
