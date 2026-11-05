package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.25.0 CPU 空转修复的验收：取帧调度必须在引导期把核让给 guest，guest 忙时退避，
 * 画面静止时降频，且永远不返回忙循环的 0 间隔。
 */
class FramePacerTest {

    @Test
    fun `boot stage uses 2 FPS to leave CPU to XNU`() {
        for (s in 0..5) {
            assertEquals(
                "阶段 $s 应该是 2 FPS",
                FramePacer.BOOT_INTERVAL_MS,
                FramePacer.nextIntervalMs(s, 0, 0, 0, frames = 10),
            )
        }
    }

    @Test
    fun `first frame ever is probed slowly`() {
        assertEquals(
            FramePacer.BOOT_INTERVAL_MS,
            FramePacer.nextIntervalMs(3, 0, 0, 0, frames = 0),
        )
    }

    @Test
    fun `active desktop returns to normal 8 FPS`() {
        assertEquals(
            FramePacer.ACTIVE_INTERVAL_MS,
            FramePacer.nextIntervalMs(7, 500, 80, changedFrames = 5, frames = 40),
        )
    }

    @Test
    fun `static picture drops to 1 FPS`() {
        assertEquals(
            FramePacer.IDLE_INTERVAL_MS,
            FramePacer.nextIntervalMs(7, 30_000, 80, changedFrames = 5, frames = 200),
        )
        // 阶段 6 且从未换过内容（黑屏/静止）也不该 8 FPS 猛压 QEMU
        assertEquals(
            FramePacer.IDLE_INTERVAL_MS,
            FramePacer.nextIntervalMs(6, 30_000, 80, changedFrames = 0, frames = 200),
        )
    }

    @Test
    fun `slow screendump backs off instead of piling up`() {
        // 真机日志里出现过 76430ms 的一次取帧；旧循环 125ms 后又发一条，形成雪崩。
        val paced = FramePacer.nextIntervalMs(7, 500, 76_430, changedFrames = 5, frames = 40)
        assertEquals(FramePacer.BACKOFF_INTERVAL_MS, paced)
        assertTrue(paced > FramePacer.ACTIVE_INTERVAL_MS)
    }

    @Test
    fun `guest busy backoff wins over active fps`() {
        assertEquals(
            FramePacer.BACKOFF_INTERVAL_MS,
            FramePacer.nextIntervalMs(7, 0, 3_000, changedFrames = 9, frames = 99),
        )
    }

    @Test
    fun `never returns a busy-loop interval`() {
        for (stage in 0..7) {
            for (cost in listOf(0L, 100L, 1_499L, 1_500L, 90_000L)) {
                assertTrue(
                    FramePacer.nextIntervalMs(stage, 0, cost, 1, frames = 5) >= FramePacer.MIN_INTERVAL_MS,
                )
            }
        }
    }

    @Test
    fun `custom normal interval is respected when active`() {
        assertEquals(
            250L,
            FramePacer.nextIntervalMs(7, 100, 50, changedFrames = 2, frames = 10, normalIntervalMs = 250L),
        )
    }

    @Test
    fun `describe covers every pace`() {
        assertTrue(FramePacer.describe(FramePacer.BACKOFF_INTERVAL_MS).contains("退避"))
        assertTrue(FramePacer.describe(FramePacer.IDLE_INTERVAL_MS).contains("静止"))
        assertTrue(FramePacer.describe(FramePacer.BOOT_INTERVAL_MS).contains("启动期"))
        assertTrue(FramePacer.describe(FramePacer.ACTIVE_INTERVAL_MS).contains("活跃"))
    }

    @Test
    fun `boot pacing saves most screendump bandwidth`() {
        // 2 FPS vs 8 FPS = 8MB * 6 次/秒 ≈ 48 MB/s 少写闪存，也少 6 次 HMP 阻塞。
        val saved = (1000f / FramePacer.ACTIVE_INTERVAL_MS - 1000f / FramePacer.BOOT_INTERVAL_MS) * 8
        assertTrue("引导期每秒应少约 48MB 的 PPM 写入", saved >= 45f)
    }
}
