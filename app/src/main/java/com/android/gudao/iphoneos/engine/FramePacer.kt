package com.android.gudao.iphoneos.engine

/**
 * 取帧调度：按目标帧率发 `screendump`，只对「取帧本身过慢」退避。
 *
 * 真机日志（HONOR 90，2026-11-04）里最贵的一行不是内核 panic，而是
 * `取帧耗时 76430ms（guest 忙或 PPM 8031KB）`。`screendump` 每次让 QEMU 把
 * 1125×2436 的 ramfb 写成 8 MB PPM，App 再读回 8 MB、解码成 11 MB 的像素数组。
 * guest 忙的时候一次 dump 要几十秒，旧循环紧接着又发下一条，形成
 * 「越卡越取、越取越卡」的空转。
 *
 * 规则（纯 Kotlin、不碰 Android，全部可在 JVM 单测里断言）：
 *  - **不设引导期 / 静止期帧率锁**：引导和桌面都按同一个目标间隔发请求，
 *    这样 Logo→桌面这段动画不会因为「阶段低」被人为降帧。
 *  - **取帧本身慢就是上限**：目标间隔只是「不要比这更快」的下限预算；
 *    真机一次 screendump 要几十毫秒，管线不会为追目标而排队堆积。
 *  - **上一次取帧超时 → 退避**：guest 忙时不再连锁发 screendump，避免 76 秒那种
 *    堆积；等它缓过来再试。这是过载保护，不是帧率上限。
 *
 * 这个对象只决定「下一帧等多久」，不发命令、不读文件，因此是整条性能链路里
 * 最容易验证的一环。
 */
object FramePacer {

    /**
     * 目标间隔：60 FPS。
     *
     * 60 FPS 是**请求预算**，不是承诺的实测帧率：一次 `screendump` + PPM 解码在本机
     * 实测约 40–56 ms（2026-11-18 的 17.79 个样本/秒），Adreno 全画幅回读更是每帧
     * 0.6–1.6 秒。因此这里给的是「允许的最快节奏」，实际帧率由 [CapturePipeline]
     * 测得的 `fpsEma` 汇报，不能用目标值冒充。
     */
    const val TARGET_INTERVAL_MS = 1000L / 60

    /**
     * 画面静止多久之后把采样降到 [STATIC_INTERVAL_MS]。
     *
     * 为什么要降：每次 `screendump` 都要 QEMU 主循环把显存写成 PPM，而主循环和 vCPU
     * 之间要抢 BQL。来宾 0.1–0.3 FPS 时，99% 的采样拍到的都是同一张图——这些采样没有
     * 信息量，只是在和 TCG 抢主循环。静止 2 秒后降到 4 次/秒，画面一动立刻回到 60。
     */
    const val STATIC_AFTER_MS = 2_000L

    /** 静止时的采样间隔。取 250 ms：点一下到画面更新最多多等 250 ms，肉眼看不出。 */
    const val STATIC_INTERVAL_MS = 250L

    /** 兼容旧名：活跃画面与引导期都用同一个目标间隔。 */
    const val ACTIVE_INTERVAL_MS = TARGET_INTERVAL_MS

    /** 保留旧常量名给调用方/诊断引用；不再作为引导期降频使用。 */
    const val BOOT_INTERVAL_MS = TARGET_INTERVAL_MS

    /** 保留旧常量名；静止画面也不降频，避免「点了没反应」的 1 秒延迟。 */
    const val IDLE_INTERVAL_MS = TARGET_INTERVAL_MS

    /** 连续静止多久算「静止」（保留给诊断文案，当前不影响节奏）。 */
    const val IDLE_AFTER_MS = 5_000L

    /** 单次取帧超过这个耗时就认为 guest 忙，下一帧退避。 */
    const val SLOW_CAPTURE_MS = 1_500L

    /** guest 忙时的退避间隔，也避免把 HMP monitor 堵死。 */
    const val BACKOFF_INTERVAL_MS = 1_800L

    /**
     * 容错下限：任何情况下都不低于这个值，避免忙循环。
     *
     * 0.32.0 从 100 ms 降到 8 ms：60 FPS 的目标间隔是 16 ms，旧的 100 ms 下限本身
     * 就成了「最多 10 FPS」的隐形锁。8 ms 仍能挡住 0 ms 忙循环。
     */
    const val MIN_INTERVAL_MS = 8L

    /**
     * @param lastCaptureMs 上一次取帧耗时（毫秒），0 表示还没有样本
     * @param normalIntervalMs 目标间隔（默认 60 FPS）
     * @param staticForMs 画面已经多少毫秒没有变化；0 表示「刚变过/未知」，不降频
     */
    fun nextIntervalMs(
        lastCaptureMs: Long,
        normalIntervalMs: Long = TARGET_INTERVAL_MS,
        staticForMs: Long = 0L,
    ): Long {
        val base = normalIntervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        // 优先级：guest 忙 > 画面静止 > 目标间隔。两者都只影响「下一帧」，不会永久锁死。
        val paced = when {
            lastCaptureMs >= SLOW_CAPTURE_MS -> maxOf(base, BACKOFF_INTERVAL_MS)
            staticForMs >= STATIC_AFTER_MS -> maxOf(base, STATIC_INTERVAL_MS)
            else -> base
        }
        return paced.coerceAtLeast(MIN_INTERVAL_MS)
    }

    /** 一行给人看的当前策略，写进 AppLog / MCP perf_stats。 */
    fun describe(intervalMs: Long): String = when {
        intervalMs >= BACKOFF_INTERVAL_MS -> "退避（guest 忙，避免 screendump 堆积）"
        intervalMs >= STATIC_INTERVAL_MS -> "静止降采样（画面没变，把主循环让给 TCG）"
        intervalMs <= TARGET_INTERVAL_MS -> "目标 60 FPS（实测受 screendump 耗时限制）"
        else -> "目标 ${(1000f / intervalMs).let { "%.0f".format(it) }} FPS"
    }
}
