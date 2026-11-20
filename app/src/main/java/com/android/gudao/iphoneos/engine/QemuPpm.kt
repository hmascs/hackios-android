package com.android.gudao.iphoneos.engine

/** QEMU P6 dumps: standard packed RGB or old Pixman rows padded to four bytes. */
internal object QemuPpm {
    data class Layout(val width: Int, val height: Int, val offset: Int, val stride: Int)
    data class Stats(val mean: Int, val signature: Long, val sceneHasContent: Boolean)

    /** A logo or bottom-only boot log is not evidence of a lock/home screen.
     * Scan at most 128 samples above the log band, looking for light outside the central logo strip. */
    internal fun sceneHasContent(argb: IntArray, w: Int, h: Int): Boolean {
        if (w < 16 || h < 16) return false
        var rows = 0
        for (row in 0 until 16) {
            val y = ((row * 2 + 1) * (h * 3 / 4)) / 32
            var hits = 0
            for (col in 0 until 12) {
                if (col in 4..7) continue
                val x = ((col * 2 + 1) * w) / 24
                val pixel = argb[y * w + x]
                val brightness = ((pixel ushr 16) and 255) + ((pixel ushr 8) and 255) + (pixel and 255)
                if (brightness >= 54) hits++
            }
            if (hits >= 2 && ++rows >= 4) return true
        }
        return false
    }

    fun layout(bytes: ByteArray, size: Int): Layout? {
        if (size !in 14..bytes.size || bytes[0] != 80.toByte() || bytes[1] != 54.toByte()) return null
        var i = 2
        val end = minOf(size, 128)
        fun whitespace(b: Byte): Boolean = b == 32.toByte() || b == 9.toByte() || b == 10.toByte() || b == 13.toByte()
        fun number(): Int? {
            if (i >= end || !whitespace(bytes[i])) return null
            while (i < end && whitespace(bytes[i])) i++
            var value = 0
            val start = i
            while (i < end && bytes[i] in 48..57) {
                value = value * 10 + bytes[i++] - 48
                if (value > 16_384) return null
            }
            return value.takeIf { i > start && i < end }
        }
        val w = number()?.takeIf { it > 0 } ?: return null
        val h = number()?.takeIf { it > 0 } ?: return null
        if (number() != 255 || !whitespace(bytes[i])) return null
        // Only consume the header's delimiter. The first pixel can itself start with 0x20/0x0a.
        val sep = bytes[i++]
        if (sep == 13.toByte() && i < size && bytes[i] == 10.toByte()) i++
        val stride = (size - i) / h
        val packed = w * 3
        if ((size - i) % h != 0 || (stride != packed && stride != ((packed + 3) and -4))) return null
        return Layout(w, h, i, stride)
    }

    fun decode(bytes: ByteArray, layout: Layout, w: Int, h: Int, target: IntArray): Stats {
        for (y in 0 until h) {
            var src = layout.offset + y * layout.stride
            val dst = y * w
            for (x in 0 until w) {
                target[dst + x] = 0xff000000.toInt() or
                    ((bytes[src++].toInt() and 255) shl 16) or
                    ((bytes[src++].toInt() and 255) shl 8) or
                    (bytes[src++].toInt() and 255)
            }
        }
        return stats(target, w, h)
    }

    /**
     * 对已经转成 ARGB 的整帧做稀疏采样：均值、指纹、是否有内容。
     *
     * 抽出来是为了让两条取帧路径（screendump 解码 / 帧环拷贝）用同一套参数算指纹：
     * 换路径时 `changedFrames`、`contentFps` 的含义必须完全不变，否则同一张画面会被
     * 判成「变了」，指标就开始说谎。采样步长与原先的 decode 完全一致。
     */
    internal fun stats(target: IntArray, w: Int, h: Int): Stats {
        // Keep statistics out of the hot conversion loop; inspect only the sampled pixels.
        val pixels = w * h
        val step = (pixels / 28_000).coerceAtLeast(1) + 1
        var sum = 0L
        var hash = -0x340d631b7bdddcdbL
        var count = 0
        for (i in 0 until pixels step step) {
            val rgb = target[i]
            sum += ((rgb ushr 16) and 255) + ((rgb ushr 8) and 255) + (rgb and 255)
            hash = (hash xor (rgb.toLong() and 0xffffffffL)) * 0x100000001b3L
            count++
        }
        return Stats((sum / (count * 3)).toInt(), hash, sceneHasContent(target, w, h))
    }
}
