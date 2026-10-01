package mindustryX.features

import arc.files.Fi
import arc.util.Time
import arc.util.io.ByteBufferOutput
import arc.util.io.Reads
import arc.util.io.Writes
import mindustry.net.Net
import mindustry.net.Packet
import mindustry.net.Streamable
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.*
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

data class ReplayData @JvmOverloads constructor(
    val version: Int,
    val time: Date,
    val serverIp: String,
    val recordPlayer: String,
    var tail: Tail? = null,
) {
    data class Tail(
        val formatVersion: Int,
        val totalTicks: Float,
        val packetCount: Int,
    )

    class Writer(private val outputStream: OutputStream) : Closeable {
        private val deflate = DeflaterOutputStream(outputStream)
        val writes = DataOutputStream(deflate)
        private val startTime = Time.time
        private val tmpBuf: ByteBuffer = ByteBuffer.allocate(32768)
        private val tmpWr: Writes = Writes(ByteBufferOutput(tmpBuf))
        private var packetCount = 0
        private var finished = false

        fun writeHeader(meta: ReplayData) {
            writes.writeInt(meta.version)
            writes.writeLong(meta.time.time)
            writes.writeUTF(meta.serverIp)
            writes.writeUTF(meta.recordPlayer)
        }

        fun writePacket(packet: Packet) {
            val id = Net.getPacketId(packet).toUInt()
            writes.writeFloat(Time.time - startTime)
            writes.writeByte(id.toInt())
            packetCount++

            if (packet is Streamable) packet.stream.apply {
                mark(available())
                writes.writeVarShort(available())
                copyTo(writes)
                reset()
            } else {
                tmpBuf.position(0)
                try {
                    // 一些IO函数会判断是否是server，所以需要临时设置。
                    Net.fakeServer = true
                    packet.write(tmpWr)
                } finally {
                    Net.fakeServer = false
                }
                writes.writeVarShort(tmpBuf.position())
                writes.write(tmpBuf.array(), 0, tmpBuf.position())
            }
        }

        fun writeTail(tail: Tail) {
            DataOutputStream(outputStream).apply {
                writeInt(tail.formatVersion)
                writeFloat(tail.totalTicks)
                writeInt(tail.packetCount)
                writeInt(tailPayloadSize)
                writeInt(tailMagic)
                flush()
            }
        }

        private fun finish() {
            if (finished) return
            finished = true
            writes.flush()
            deflate.finish()
            writeTail(Tail(formatVersion, Time.time - startTime, packetCount))
        }

        override fun close() {
            finish()
            outputStream.close()
        }

        private fun DataOutputStream.writeVarShort(value: Int) {
            if (value > Short.MAX_VALUE) {
                writeInt((1 shl 31) or value)
            } else {
                writeShort(value)
            }
        }
    }

    class Reader(inputStream: InputStream) : Closeable {
        var source: Fi? = null

        /** 尾部元数据；非文件来源或无 tail 时为 null。 */
        val tail: Tail? = readTailStream(inputStream)
        val reads: DataInputStream = DataInputStream(InflaterInputStream(inputStream.buffered(32768)))
        val meta: ReplayData = readHeader().also { it.tail = tail }

        constructor(fi: Fi) : this(fi.read()) {
            source = fi
        }

        /** 旧 arc 格式（版本号 <= 10，与 mrep 的游戏版本号不是一个体系）。 */
        val arcOldFormat = meta.version <= 10
        private val readsWrap = Reads(reads)

        private fun readHeader(): ReplayData {
            val version = reads.readInt()
            val time = Date(reads.readLong())
            val serverIp = reads.readUTF()
            val recordPlayer = reads.readUTF()
            return ReplayData(version, time, serverIp, recordPlayer)
        }

        @Throws(EOFException::class)
        fun nextPacket(): PacketInfo {
            val offset = if (!arcOldFormat) {
                reads.readFloat()
            } else {
                reads.readLong() * Time.toSeconds / Time.nanosPerMilli / 1000
            }
            val id = reads.readByte()
            val length = reads.readVarShort()
            return PacketInfo(offset, id, length)
        }

        @Throws(IOException::class)
        fun readPacket(info: PacketInfo): Packet {
            val p = Net.newPacket<Packet>(info.id)
            if (p is Streamable) {
                val bs = ByteArray(info.length)
                reads.readFully(bs)
                p.stream = ByteArrayInputStream(bs)
            } else {
                p.read(readsWrap, info.length)
            }
            return p
        }

        fun allPacket(): List<PacketInfo> = buildList {
            while (true) {
                try {
                    val info = nextPacket()
                    reads.skip(info.length.toLong())
                    add(info)
                } catch (_: EOFException) {
                    break
                }
            }
        }

        /** 用同一个文件重新打开一个 Reader，用于跳转回退；非文件来源返回 null。 */
        fun reopen(): Reader? = source?.let { Reader(it) }

        override fun close() {
            reads.close()
        }

        private fun DataInputStream.readVarShort(): Int {
            val high = readUnsignedShort()
            return if (high and 0x8000 != 0) {
                val low = readUnsignedShort()
                ((high and 0x7FFF) shl 16) + low
            } else {
                high
            }
        }
    }

    data class PacketInfo(
        val offset: Float,
        val id: Byte,
        val length: Int,
    )

    companion object {
        /** 容器格式版本。 */
        const val formatVersion = 1
        private const val tailMagic = 0x4D525054 // 'MRPT'
        private const val tailPayloadSize = 12 // formatVersion + totalTicks + packetCount
        private const val tailFooterSize = 8 // payloadSize + magic

        /** 从文件流尾部读元数据；非文件流或没有 tail 返回 null。位置参数读取，不影响流的位置。 */
        private fun readTailStream(input: InputStream): Tail? {
            if (input !is FileInputStream) return null
            val channel = input.channel
            return try {
                val length = channel.size()
                if (length < tailFooterSize + tailPayloadSize) return null

                //末尾 8 字节是 payloadSize + magic，payload 在它之前；后续追加的字段会被忽略
                val footer = channel.readAt(length - tailFooterSize, tailFooterSize) ?: return null
                val footerIn = DataInputStream(footer.inputStream())
                val payloadSize = footerIn.readInt()
                if (footerIn.readInt() != tailMagic) return null
                if (payloadSize < tailPayloadSize || payloadSize > length - tailFooterSize) return null

                val payload = channel.readAt(length - tailFooterSize - payloadSize, payloadSize) ?: return null
                val payloadIn = DataInputStream(payload.inputStream())
                val format = payloadIn.readInt()
                val ticks = payloadIn.readFloat()
                val count = payloadIn.readInt()
                if (format <= 0 || !ticks.isFinite() || ticks < 0f) return null
                Tail(format, ticks, count)
            } catch (e: IOException) {
                null
            }
        }

        /** 从 position 起读 size 字节；不足返回 null。 */
        private fun FileChannel.readAt(position: Long, size: Int): ByteArray? {
            val bytes = ByteArray(size)
            val buffer = ByteBuffer.wrap(bytes)
            var pos = position
            while (buffer.hasRemaining()) {
                val read = read(buffer, pos)
                if (read < 0) return null
                pos += read
            }
            return bytes
        }
    }
}