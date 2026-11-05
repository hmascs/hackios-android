package com.android.gudao.iphoneos.engine

/**
 * 自适应取帧调度：把 CPU 还给 QEMU 的 TCG 线程。
 *
 * 真机日志（HONOR 90，2026-11-04）里最贵的一行不是内核 panic，而是
 * `取帧耗时 76430ms（guest 忙或 PPM 8031KB）`。`screendump` 每次让 QEMU 把
 * 1125×2436 的 ramfb 写成 8 MB PPM，App 再读回 8 MB、解码成 11 MB 的像素数组；
 * 固定 8 FPS 就是 ~64 MB/s 的闪存写 + ~150 MB/s 的临时分配，全部和 TCG 的
 * vCPU 线程抢同一批物理核。guest 忙的时候一次 dump 要几十秒，旧循环紧接着
 * 又发下一条，形成「越卡越取、越取越卡」的空转。
 *
 * 规则（纯 Kotlin、不碰 Android，全部可在 JVM 单测里断言）：
 *  - **启动期（阶段 < 6）降到 2 FPS**：UEFI → XNU → launchd 这几级只需要知道
 *    「屏幕什么时候亮」，不需要 8 FPS。省下的 CPU 全部留给引导，直接缩短
 *    亮 logo / 进桌面的耗时。
 *  - **画面开始变化 → 8 FPS**：用户已经在看桌面、触摸、动画，恢复正常流畅度。
 *  - **画面静止超过 [IDLE_AFTER_MS] → 1 FPS**：同一张锁屏画面没必要每秒抓 8 次。
 *  - **上一次取帧超时 → 退避**：guest 忙时不再连锁发 screendump，避免 76 秒那种
 *    堆积；等它缓过来再试。
 *
 * 这个对象只决定「下一帧等多久」，不发命令、不读文件，因此是整条性能链路里
 * 最容易验证的一环。
 */
object FramePacer {

    /**
     * 启动阶段（还没到界面层）：1 FPS。
     *
     * 0.29.8 从 2 FPS 降到 1 FPS：实测这段要跑 10 分钟，而每秒一次的 screendump 是
     * 「QEMU 把 1125x2436 写成 8 MB PPM + App 读回解码成 11 MB」——在手机上正好和
     * TCG 的 vCPU 线程抢同一批核。这段只需要知道「屏幕什么时候亮」，1 FPS 完全够。
     */
    const val BOOT_INTERVAL_MS = 1000L

    /** 画面活跃：8 FPS，与 [com.android.gudao.iphoneos.Constants.FB_FPS] 一致。 */
    const val ACTIVE_INTERVAL_MS = 125L

    /** 静止画面：1 FPS，避免对同一张锁屏反复 dump 8 MB。 */
    const val IDLE_INTERVAL_MS = 1000L

    /** 连续静止多久算「静止」。 */
    const val IDLE_AFTER_MS = 5_000L

    /** 单次取帧超过这个耗时就认为 guest 忙，下一帧退避。 */
    const val SLOW_CAPTURE_MS = 1_500L

    /** guest 忙时的退避间隔，也避免把 HMP monitor 堵死。 */
    const val BACKOFF_INTERVAL_MS = 1_800L

    /** 容错上限：任何情况下都不低于这个值，避免忙循环。 */
    const val MIN_INTERVAL_MS = 100L

    /**
     * @param stage BootPhase 的文本阶段 0..7（7 表示已经画出桌面）
     * @param unchangedForMs 画面内容已经多久没变化
     * @param lastCaptureMs 上一次取帧耗时（毫秒），0 表示还没有样本
     * @param changedFrames 内容真正变化过的帧数
     * @param frames 已取到的帧数
     * @param normalIntervalMs 用户设定的基准间隔（默认 8 FPS）
     */
    fun nextIntervalMs(
        stage: Int,
        unchangedForMs: Long,
        lastCaptureMs: Long,
        changedFrames: Long,
        frames: Long,
        normalIntervalMs: Long = ACTIVE_INTERVAL_MS,
    ): Long {
        val base = when {
            // 还没取到任何帧：慢一点探测，别在 QEMU 刚起来时就压上去。
            frames == 0L -> BOOT_INTERVAL_MS
            // 引导阶段：CPU 优先给 guest。
            stage < 6 -> BOOT_INTERVAL_MS
            // 已经进桌面且画面在动：正常帧率。
            changedFrames > 0 && unchangedForMs < IDLE_AFTER_MS -> normalIntervalMs
            // 静止：降频。
            else -> IDLE_INTERVAL_MS
        }
        // guest 忙的退避优先级最高，且只影响「下一帧」，不会永久锁死在低帧率。
        val paced = if (lastCaptureMs >= SLOW_CAPTURE_MS) maxOf(base, BACKOFF_INTERVAL_MS) else base
        return paced.coerceAtLeast(MIN_INTERVAL_MS)
    }

    /** 一行给人看的当前策略，写进 AppLog / MCP perf_stats。 */
    fun describe(intervalMs: Long): String = when {
        intervalMs >= BACKOFF_INTERVAL_MS -> "退避（guest 忙，避免 screendump 堆积）"
        intervalMs >= IDLE_INTERVAL_MS -> "静止降频 1 FPS"
        intervalMs >= BOOT_INTERVAL_MS -> "启动期 2 FPS（CPU 让给引导）"
        else -> "活跃 ${(1000f / intervalMs).let { "%.0f".format(it) }} FPS"
    }
}
