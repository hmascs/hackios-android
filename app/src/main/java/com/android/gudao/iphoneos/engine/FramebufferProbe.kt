package com.android.gudao.iphoneos.engine

/**
 * 黑屏直读探针（0.26.1）——纯 Kotlin、无 Android 依赖、可 JVM 单测。
 *
 * 真机证据链（hackios-logs-20261104-230859.txt）：
 *  1. 串口有 `display 1125 x 2436 at 1fb6bb000`（内核报告 boot framebuffer 的 guest 物理地址）
 *     与 `MiniIOMobileFramebuffer: display 1125x2436 on a panel of 1125x2436 (4500 bytes per row)`。
 *  2. WindowServer 只提交 2 次 swap（surface 3/4，BGRA 1125x2436）后停摆，之后几分钟无一帧。
 *  3. QEMU screendump（读 ramfb surface）全程黑，只有 loader 画的 Apple logo。
 *
 * 结论：断点在「IOSurface 内容没进 ramfb 的 boot framebuffer」。宿主侧能做的最有力一步是
 * 用 HMP `pmemsave` 把 0x1fb6bb000 那块 guest 物理内存导出来直接判读：
 *  - 非黑 → 内核确实往 boot framebuffer 写了，只是 QEMU ramfb surface 不是这块内存；
 *    App 可以走「直读显示」绕过 ramfb（实验路径）。
 *  - 黑   → 只有「这一帧在这个地址上是黑的」。0.29.4 复跑证明内核的 swap 拷贝是工作的
 *           （串口有 swap 记录，且同一地址能看到内核画的 logo）；不要把黑直接当成缺 blit。
 *
 * 这里只做解析与判读（不碰 Socket/Bitmap），让判读逻辑可以脱离真机被单测钉住。
 */
object FramebufferProbe {

    /** 内核/MiniIOMFB 串口报告的 guest 显示参数。 */
    data class GuestDisplay(
        val width: Int,
        val height: Int,
        val fbAddr: Long,
        val stride: Int,
    ) {
        val byteSize: Long get() = stride.toLong() * height
    }

    // 内核早期打印：`display 1125 x 2436 at 1fb6bb000`
    private val KERNEL_DISPLAY = Regex(
        "display (\\d{2,5}) x (\\d{2,5}) at ([0-9a-fA-F]{4,16})(?!\\w)",
    )
    // MiniIOMFB 打印：`display 1125x2436 on a panel of 1125x2436 (4500 bytes per row)`
    private val IOMFB_DISPLAY = Regex(
        "display (\\d{2,5})x(\\d{2,5}) on a panel of \\d{2,5}x\\d{2,5} \\((\\d{3,10}) bytes per row\\)",
    )

    /**
     * 从一行串口日志解析显示参数。两个来源互补：内核行给出地址，MiniIOMFB 行给出 stride。
     * 任何一行都能给出 width/height；地址或 stride 缺失时用合理默认（addr=0 表示未知）。
     */
    fun parseDisplayLine(line: String): GuestDisplay? {
        KERNEL_DISPLAY.find(line)?.let { m ->
            val w = m.groupValues[1].toInt()
            val h = m.groupValues[2].toInt()
            val addr = m.groupValues[3].toLong(16)
            // MiniIOMFB 的 stride 行可能出现在别处；这里先给最常见默认 w*4（BGRA）。
            val stride = IOMFB_DISPLAY.find(line)?.groupValues?.get(3)?.toIntOrNull() ?: (w * 4)
            if (w in 100..8000 && h in 100..8000 && stride >= w * 4) {
                return GuestDisplay(w, h, addr, stride)
            }
        }
        IOMFB_DISPLAY.find(line)?.let { m ->
            val w = m.groupValues[1].toInt()
            val h = m.groupValues[2].toInt()
            val stride = m.groupValues[3].toInt()
            if (w in 100..8000 && h in 100..8000 && stride >= w * 4) {
                return GuestDisplay(w, h, 0L, stride)
            }
        }
        return null
    }

    /** 两行合并：addr 优先取有值的一侧，stride 同理（串口多行报告同一显示器）。 */
    fun merge(a: GuestDisplay?, b: GuestDisplay?): GuestDisplay? {
        if (a == null) return b
        if (b == null) return a
        return GuestDisplay(
            width = if (a.width > 100) a.width else b.width,
            height = if (a.height > 100) a.height else b.height,
            fbAddr = if (a.fbAddr != 0L) a.fbAddr else b.fbAddr,
            stride = if (a.stride > 0) a.stride else b.stride,
        )
    }

    /**
     * 对 pmemsave 导出的 BGRA 帧算平均亮度（与 CapturePipeline 的 0..765 口径一致：
     * 每像素 (R+G+B)，均值 0=全黑、765=全白）。stride 允许大于 w*4（行尾 padding 跳过）。
     * 长度不足按能算的行算；一行都算不了返回 -1。
     */
    fun computeMeanBgra(bytes: ByteArray, display: GuestDisplay): Int {
        val w = display.width
        val h = display.height
        val stride = display.stride
        if (w <= 0 || h <= 0 || stride < w * 4) return -1
        var sum = 0L
        var count = 0
        var row = 0
        // 步进取样：至多 ~200 行、每行 ~200 列，够判黑/白/有内容，避免 10MB 全扫。
        val rowStep = maxOf(1, h / 200)
        val colStep = maxOf(1, w / 200)
        while (row < h) {
            val base = row.toLong() * stride
            if (base + w * 4L > bytes.size) break
            var col = 0
            while (col < w) {
                val i = (base + col * 4L).toInt()
                // BGRA：B,G,R,A。亮度用 R+G+B。
                val b = bytes[i].toInt() and 0xFF
                val g = bytes[i + 1].toInt() and 0xFF
                val r = bytes[i + 2].toInt() and 0xFF
                sum += r + g + b
                count++
                col += colStep
            }
            row += rowStep
        }
        return if (count == 0) -1 else (sum / count).toInt()
    }

    /** screendump 判黑口径：mean <= 8 视为黑（CapturePipeline BLACK_MEAN 相同量级）。 */
    const val BLACK_MEAN: Int = 8

    /** 探针结论。 */
    enum class Verdict { NON_BLACK, BLACK, UNREADABLE }

    fun verdict(mean: Int): Verdict = when {
        mean < 0 -> Verdict.UNREADABLE
        mean <= BLACK_MEAN -> Verdict.BLACK
        else -> Verdict.NON_BLACK
    }
}
