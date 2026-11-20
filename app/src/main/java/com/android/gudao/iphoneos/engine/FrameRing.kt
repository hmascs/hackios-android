package com.android.gudao.iphoneos.engine

import java.io.File
import java.io.RandomAccessFile
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * worker → App 帧环的读端（协议 v2，见 docs/gl-frame-ring.md）。
 *
 * 为什么不是 screendump：每帧要 QEMU 主循环写 8 MB PPM，App 再读回逐像素解码，两端合计
 * 70–97 ms，还会占住 QEMU 的 BQL 跟来宾抢 CPU。worker 已经渲染好的像素就在共享内存里，
 * 按 stride 读一遍即可；这里只是读端，写端（以及没有写端时的回退）由调用方负责。
 *
 * 纯 Kotlin + java.nio，不依赖 Android 类：单测可以在 JVM 上直接映射临时文件。
 */
class FrameRing private constructor(
    private val mapped: ByteBuffer,
    val width: Int,
    val height: Int,
    private val stride: Int,
) {
    /** worker 写最近一帧的 CLOCK_MONOTONIC 纳秒，先只留作诊断。 */
    @Volatile
    var timestampNs: Long = 0L
        private set

    // A reusable little-endian word view allows native bulk transfer. Position
    // belongs to the one capture reader; the writer uses its own process map.
    private val words = mapped.asIntBuffer()
    private var lastSeq = 0L

    /** 头/尺寸坏掉时只记一条日志：帧环每 16 ms 被问一次，不能让故障变成刷屏。 */
    private var loggedMismatch = false
    private var failed = false

    /**
     * 把最新一帧拷进 [target]（0xAARRGGBB，正是 `Bitmap.setPixels` 要的格式）。
     *
     * 返回 false 表示「没有新完整帧」或「环不可用」，不把工作数组提交为画面。
     * 调用方按既有停滞超时策略回退 screendump；帧环不是唯一显示来源。
     */
    fun latestInto(target: IntArray, width: Int, height: Int): Boolean =
        copyInto(target, width, height, null, null)

    /**
     * 单测专用入口：[afterCopy] 在像素拷完、seq 复核之前调用，用来确定性地复现
     * 「拷贝途中 worker 又发布了下一帧」。生产路径永远传 null。
     */
    internal fun latestIntoForTest(
        target: IntArray,
        width: Int,
        height: Int,
        beforeCopy: (() -> Unit)? = null,
        afterCopy: () -> Unit,
    ): Boolean = copyInto(target, width, height, beforeCopy, afterCopy)

    private fun copyInto(
        target: IntArray,
        width: Int,
        height: Int,
        beforeCopy: (() -> Unit)?,
        afterCopy: (() -> Unit)?,
    ): Boolean {
        if (failed) return false
        val atomic = ATOMIC_LONG ?: return false
        if (width != this.width || height != this.height || target.size < width * height) {
            if (!loggedMismatch) {
                loggedMismatch = true
                AppLog.w(
                    TAG,
                    "帧环尺寸 ${this.width}×${this.height} 与取帧目标 ${width}×${height} 不符，回退 screendump",
                )
            }
            return false
        }
        return try {
            // A single slot needs an invalidation BEFORE the writer overwrites it.
            // Publication count alone cannot detect an unfinished next frame.
            val guard = atomic.getAcquire(mapped, GUARD_OFFSET) as Long
            if ((guard and 1L) != 0L) return false
            val seq = atomic.getAcquire(mapped, SEQ_OFFSET) as Long
            if (seq == 0L || seq == lastSeq || guard != seq * 2) return false
            if (mapped.getInt(WIDTH_OFFSET) != this.width || mapped.getInt(HEIGHT_OFFSET) != this.height ||
                mapped.getInt(STRIDE_OFFSET) != stride || mapped.getInt(FORMAT_OFFSET) != FORMAT_BGRA) {
                return false
            }
            beforeCopy?.invoke()
            // BGRA bytes interpreted as little-endian words are already ARGB.
            // The normal packed panel needs one bulk transfer, avoiding millions
            // of per-pixel JVM calls. Padded rows still skip their padding.
            if (stride == width * 4) {
                words.position(PIXELS_OFFSET / 4)
                words.get(target, 0, width * height)
            } else if (stride % 4 == 0) {
                for (y in 0 until height) {
                    words.position((PIXELS_OFFSET + y * stride) / 4)
                    words.get(target, y * width, width)
                }
            } else {
                // The protocol allows byte padding, including unaligned rows.
                for (y in 0 until height) {
                    var src = PIXELS_OFFSET + y * stride
                    var dst = y * width
                    val end = src + width * 4
                    while (src < end) {
                        target[dst++] = mapped.getInt(src)
                        src += 4
                    }
                }
            }
            afterCopy?.invoke()
            // Keep pixel/metadata reads before the final guard load. A final
            // acquire alone only orders the reads that follow that load.
            val stamp = mapped.getLong(TIMESTAMP_OFFSET)
            VarHandle.loadLoadFence()
            if ((atomic.getAcquire(mapped, GUARD_OFFSET) as Long) != guard) return false
            timestampNs = stamp
            lastSeq = seq
            true
        } catch (t: Throwable) {
            // 文件被截短或 worker 异常都可能让映射读越界。失败一次就永久回退 screendump，
            // 而不是每一拍都抛异常、把「画面没了」变成「日志爆炸」。
            failed = true
            AppLog.w(TAG, "帧环读取失败，回退 screendump：${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "FrameRing"
        private const val HEADER_BYTES = 64
        private const val VERSION = 2
        private const val FORMAT_BGRA = 1
        private const val WIDTH_OFFSET = 8
        private const val HEIGHT_OFFSET = 12
        private const val STRIDE_OFFSET = 16
        private const val FORMAT_OFFSET = 20
        private const val SEQ_OFFSET = 24
        private const val TIMESTAMP_OFFSET = 32
        private const val GUARD_OFFSET = 40
        private const val PIXELS_OFFSET = 64
        /** 宽/高/stride 的上限，用来挡住坏头引发的巨量分配或越界乘法。 */
        private const val MAX_EDGE = 16_384

        private val ATOMIC_LONG: VarHandle? = runCatching {
            MethodHandles.byteBufferViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)
                .takeIf { it.isAccessModeSupported(VarHandle.AccessMode.GET_ACQUIRE) }
        }.getOrNull()

        /**
         * 打开并校验帧环；任何一项不对都返回 null（调用方据此走 screendump）。
         * 只读映射，文件由 worker 写。
         */
        fun open(path: File): FrameRing? {
            val atomic = ATOMIC_LONG ?: run {
                AppLog.w(TAG, "帧环原子能力不可用，回退 screendump")
                return null
            }
            val length = if (path.isFile) path.length() else 0L
            if (length < HEADER_BYTES) {
                AppLog.w(TAG, "帧环不可用：${path.name} 长度 $length 不足 $HEADER_BYTES 字节头")
                return null
            }
            if (length > Int.MAX_VALUE) {
                AppLog.w(TAG, "帧环不可用：${path.name} 长度 $length 超出映射上限")
                return null
            }
            val mapped = try {
                RandomAccessFile(path, "r").use { raf ->
                    raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, length)
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "帧环映射失败：${t.message}")
                return null
            }
            mapped.order(ByteOrder.LITTLE_ENDIAN)
            if (mapped.get(0) != 'H'.code.toByte() || mapped.get(1) != 'F'.code.toByte() ||
                mapped.get(2) != 'R'.code.toByte() || mapped.get(3) != '2'.code.toByte()
            ) {
                AppLog.w(TAG, "帧环不可用：魔数不是 HFR2（旧协议回退 screendump）")
                return null
            }
            val version = mapped.getInt(4)
            if (version != VERSION) {
                AppLog.w(TAG, "帧环不可用：协议版本 $version，App 只认 $VERSION")
                return null
            }
            val format = mapped.getInt(FORMAT_OFFSET)
            if (format != FORMAT_BGRA) {
                AppLog.w(TAG, "帧环不可用：像素格式 $format，App 只认 BGRA8888（$FORMAT_BGRA）")
                return null
            }
            val width = mapped.getInt(WIDTH_OFFSET)
            val height = mapped.getInt(HEIGHT_OFFSET)
            val stride = mapped.getInt(STRIDE_OFFSET)
            if (width !in 1..MAX_EDGE || height !in 1..MAX_EDGE ||
                stride < width * 4 || stride > MAX_EDGE * 4
            ) {
                AppLog.w(TAG, "帧环不可用：几何参数异常 ${width}×${height}，stride=$stride")
                return null
            }
            // 长度按协议的 64 + stride*height 校验：短文件会把「worker 还没写满」误读成有效帧。
            val needed = HEADER_BYTES.toLong() + stride.toLong() * height
            if (length < needed) {
                AppLog.w(TAG, "帧环不可用：文件 $length 字节，按 ${width}×${height}/stride=$stride 需要 $needed")
                return null
            }
            return try {
                val guard = atomic.getAcquire(mapped, GUARD_OFFSET) as Long
                val stamp = mapped.getLong(TIMESTAMP_OFFSET)
                VarHandle.loadLoadFence()
                FrameRing(mapped, width, height, stride).also {
                    if ((guard and 1L) == 0L && (atomic.getAcquire(mapped, GUARD_OFFSET) as Long) == guard) {
                        it.timestampNs = stamp
                    }
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "帧环原子读取不可用，回退 screendump：${t.message}")
                null
            }
        }

        /**
         * 创建（或截断）环文件并写入 seq=0 的空头。
         *
         * 为什么要 App 先写头：`open` 会校验魔数，worker 可能要到第一帧渲染完才写头；
         * 如果等它写，App 只能在整个会话里都拿不到环。这里先按协议填好只读字段，
         * worker 随后照常覆盖（seq 仍从 0 开始），两端都不会把空文件误当成一帧。
         */
        fun create(path: File, width: Int, height: Int, stride: Int): Boolean {
            if (ATOMIC_LONG == null) return false
            if (width !in 1..MAX_EDGE || height !in 1..MAX_EDGE || stride < width * 4 || stride > MAX_EDGE * 4) return false
            val total = HEADER_BYTES.toLong() + stride.toLong() * height
            if (total > Int.MAX_VALUE) return false
            return try {
                path.parentFile?.mkdirs()
                RandomAccessFile(path, "rw").use { raf ->
                    raf.setLength(total)
                    val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    header.put(0, 'H'.code.toByte())
                    header.put(1, 'F'.code.toByte())
                    header.put(2, 'R'.code.toByte())
                    header.put(3, '2'.code.toByte())
                    header.putInt(4, VERSION)
                    header.putInt(WIDTH_OFFSET, width)
                    header.putInt(HEIGHT_OFFSET, height)
                    header.putInt(STRIDE_OFFSET, stride)
                    header.putInt(FORMAT_OFFSET, FORMAT_BGRA)
                    header.putLong(SEQ_OFFSET, 0L)
                    header.putLong(TIMESTAMP_OFFSET, 0L)
                    header.putLong(GUARD_OFFSET, 0L)
                    // 48..63 are reserved. The writer never changes geometry mid-session.
                    raf.seek(0)
                    raf.write(header.array())
                }
                true
            } catch (t: Throwable) {
                AppLog.w(TAG, "帧环创建失败：${t.message}")
                false
            }
        }
    }
}
