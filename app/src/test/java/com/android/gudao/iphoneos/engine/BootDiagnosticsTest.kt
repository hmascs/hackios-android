package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.26.0 启动诊断日志：结构化维度行、事件检测、密度对照（≥3 倍且不靠灌水）。 */
class BootDiagnosticsTest {

    private fun snap(elapsed: Long, stage: Int, extra: BootDiagnostics.Snapshot.() -> BootDiagnostics.Snapshot = { this }) =
        BootDiagnostics.Snapshot(
            elapsedMs = elapsed, stage = stage, textStage = stage,
            label = "阶段 $stage/7", serialDeltaLines = 12, serialTotalLines = elapsed / 1000 * 12,
            serialLinesPerSec = 12.0, serialLastLine = "launchd: spawned SpringBoard[7]",
            frames = stage * 3L, changedFrames = stage.toLong(), meanBrightness = 42,
            resolution = "1125×2436", captureMs = 180, intervalMs = 500,
            framePace = "引导期 2FPS", unchangedForMs = 1200,
        ).extra()

    /** 模拟一次 180 秒、阶段 1→7 的启动采样序列。 */
    private fun bootSamples(): List<BootDiagnostics.Snapshot> = buildList {
        var stage = 1
        for (t in 0L..180_000L step 1000L) {
            if (t in setOf(20_000L, 45_000L, 70_000L, 95_000L, 120_000L, 150_000L)) stage++
            val logo = if (t >= 20_000L) 20_000L else null
            val desktop = if (t >= 150_000L) 150_000L else null
            add(snap(t, stage) {
                copy(
                    logoMs = logo, desktopMs = desktop,
                    display = if (t >= 45_000L) "fbscale=3 正常" else null,
                    persona = if (t >= 70_000L) "persona type6 非阻塞" else null,
                    desktopSignal = if (t >= 150_000L) "System shell connected" else null,
                    noise = if (t >= 95_000L) "lsd CPU monitor" else null,
                )
            })
        }
    }

    @Test
    fun `each sample renders seven dimension lines with parseable fields`() {
        val lines = BootDiagnostics.render(snap(12_000, 3))
        assertEquals(7, lines.size)
        val dims = lines.map { it.split(' ')[2] }
        assertEquals(listOf("PHASE", "SERIAL", "FRAME", "CPU", "TIMELINE", "GUEST", "HEALTH"), dims)
        lines.forEach { l ->
            assertTrue("每行必须以 [boot] 开头", l.startsWith("[boot]"))
            assertTrue("每行至少 4 个 key=value 字段：${l.take(60)}",
                BootDiagnostics.countFields(l) >= 4)
            assertFalse("单行不得含换行", l.contains('\n'))
        }
    }

    @Test
    fun `GUEST line lists diagnostics that appeared instead of a vague summary`() {
        val withSignals = snap(100_000, 6) {
            copy(display = "fbscale=3", persona = "type6", desktopSignal = "System shell connected")
        }
        val guest = BootDiagnostics.render(withSignals).first { " GUEST " in it }
        assertTrue(guest.contains("display="))
        assertTrue(guest.contains("persona="))
        assertTrue(guest.contains("desktopSignal="))
        // 无信号时也要有信息，不能空行
        val none = BootDiagnostics.render(snap(1000, 1)).first { " GUEST " in it }
        assertTrue(none.contains("signals=none"))
        assertTrue(BootDiagnostics.countFields(none) >= 4)
    }

    @Test
    fun `detectEvents fires phase jumps slow capture serial stall and fallback`() {
        val prev = snap(1000, 2)
        val cur = snap(2000, 3) {
            copy(captureMs = 2000, serialStallMs = 6000, unchangedForMs = 100_000,
                fallbackUsed = true)
        }
        val ev = BootDiagnostics.detectEvents(prev, cur)
        assertTrue(ev.any { it.startsWith("phase-jump") })
        assertTrue(ev.any { it.startsWith("slow-capture") })
        assertTrue(ev.any { it.startsWith("serial-stall") })
        assertTrue(ev.any { it.startsWith("frame-stall") })
        assertTrue(ev.any { it.startsWith("auto-fallback") })
        // 首轮给 boot-start
        assertTrue(BootDiagnostics.detectEvents(null, prev).any { it.startsWith("boot-start") })
        // 状态未变时不重复报事件
        assertTrue(BootDiagnostics.detectEvents(cur, cur.copy(elapsedMs = 3000)).isEmpty())
    }

    @Test
    fun `density is at least 3x the legacy baseline and not padded with empty fields`() {
        val samples = bootSamples()
        val d = BootDiagnostics.density(samples)
        // 旧版：开始 1 + 阶段跃迁 7 + logo 1 + desktop 1 + 退出 1 = 约 11 行
        assertTrue("基线应是个位数/十位数（精确复刻旧行为），实际=${d.baselineLines}", d.baselineLines <= 15)
        // 新版：每轮 7 行 + 少量事件
        assertTrue("增强行数应 = 采样数×7 以上，实际=${d.enhancedLines}",
            d.enhancedLines >= samples.size * 7)
        assertTrue("密度倍数应 >= 3，实际=${d.lineRatio}", d.lineRatio >= BootDiagnostics.DENSITY_TARGET)
        assertTrue("每行平均字段数应 >= 4（证明不是灌水），实际=${d.avgFieldsPerLine}",
            d.avgFieldsPerLine >= BootDiagnostics.MIN_FIELDS_PER_LINE.toDouble())
        assertTrue(d.meetsTarget)
        assertEquals(7, d.dimensionsPerSample)
        assertTrue("应覆盖整段启动采样", d.samples == samples.size)
    }

    @Test
    fun `legacyLines exactly mirrors pre-026 behaviour - baseline must not be inflated`() {
        val samples = bootSamples()
        val legacy = BootDiagnostics.legacyLines(samples)
        assertTrue(legacy.first().startsWith("开始启动"))
        assertTrue(legacy.last().startsWith("QEMU 进程退出"))
        assertTrue(legacy.any { it.startsWith("亮起 Logo") })
        assertTrue(legacy.any { it.startsWith("进入桌面") })
        // 阶段跃迁行数 = 去重后的阶段数
        val jumps = legacy.count { it.startsWith("启动阶段") }
        val distinctStages = samples.map { it.stage }.distinct().size
        assertEquals(distinctStages, jumps)
    }

    @Test
    fun `field values are sanitized so a single log line always stays one line`() {
        val dirty = snap(1000, 2) {
            copy(label = "阶段\n2/7 有 空格", serialLastLine = "a\tb\nc d=e")
        }
        BootDiagnostics.render(dirty).forEach { l ->
            assertFalse("不得含换行", l.contains('\n') || l.contains('\r'))
            assertFalse("字段值里的空格被转成下划线，行结构不散", l.contains("阶段 "))
        }
    }
}
