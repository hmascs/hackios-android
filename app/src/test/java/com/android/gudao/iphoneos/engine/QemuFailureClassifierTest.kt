package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every failure the launcher can meet has to map onto an actionable explanation. */
class QemuFailureClassifierTest {

    @Test
    fun `busy control port is reported before anything else`() {
        val f = QemuFailureClassifier.classify(
            exitCode = 1,
            qemuLog = "qemu-system-aarch64: -qmp tcp:127.0.0.1:4445: Failed to bind socket",
            serialLogExists = false,
            portBusy = listOf(4445),
        )
        assertEquals(QemuFailureKind.PortInUse, f.kind)
        assertTrue(f.hint!!.contains("残留"))
    }

    @Test
    fun `missing option rom names the rom`() {
        val f = QemuFailureClassifier.classify(
            exitCode = 1,
            qemuLog = "qemu-system-aarch64: failed to find romfile \"efi-virtio.rom\"",
            serialLogExists = false,
        )
        assertEquals(QemuFailureKind.MissingRom, f.kind)
        assertTrue(f.detail.contains("efi-virtio.rom"))
    }

    @Test
    fun `linker failure is distinguished from a guest crash`() {
        val f = QemuFailureClassifier.classify(
            exitCode = 127,
            qemuLog = "CANNOT LINK EXECUTABLE \"qemu-system-aarch64\": library \"libglib-2.0.so.0\" not found",
            serialLogExists = false,
        )
        assertEquals(QemuFailureKind.LinkerError, f.kind)
    }

    @Test
    fun `exec denial points at the targetSdk policy`() {
        val f = QemuFailureClassifier.classify(
            exitCode = 13,
            qemuLog = "java.io.IOException: Cannot run program ...: error=13, Permission denied",
            serialLogExists = false,
        )
        assertEquals(QemuFailureKind.ExecDenied, f.kind)
    }

    @Test
    fun `missing images are reported as a missing payload`() {
        val f = QemuFailureClassifier.classify(
            exitCode = null,
            qemuLog = null,
            serialLogExists = false,
            hasPayload = false,
        )
        assertEquals(QemuFailureKind.MissingPayload, f.kind)
    }

    @Test
    fun `an empty log with a non-zero exit is still explained`() {
        val f = QemuFailureClassifier.classify(1, "", serialLogExists = false)
        assertEquals(QemuFailureKind.EarlyExit, f.kind)
        assertTrue(f.detail.contains("串口还没建立"))
        assertTrue(f.retryable)
    }

    @Test
    fun `early exit with an empty qemu log says so instead of pointing at an empty panel`() {
        val f = QemuFailureClassifier.classify(1, "", serialLogExists = false)
        // 旧卡片让用户「展开日志查看最后几行」，但 qemu.log 就是空的；现在必须直接说明。
        assertTrue(f.detail.contains("qemu.log 为空"))
        assertTrue(f.hint!!.contains("内存"))
    }

    @Test
    fun `early exit carries the real qemu error text into the card`() {
        // 只含「早退」特征，不命中 romfile/端口/链接器这些更具体的规则。
        val log = "qemu-system-aarch64: Failed to allocate 1426063360 bytes for TCG buffer\n" +
            "qemu-system-aarch64: could not initialize TCG"
        val f = QemuFailureClassifier.classify(1, log, serialLogExists = false)
        assertEquals(QemuFailureKind.EarlyExit, f.kind)
        assertTrue(f.detail.contains("qemu.log 末段"))
        assertTrue(f.detail.contains("Failed to allocate 1426063360 bytes"))
        assertTrue(f.detail.contains("could not initialize TCG"))
    }

    @Test
    fun `early exit reports the boot budget and the safe retry`() {
        val f = QemuFailureClassifier.classify(
            1, "", serialLogExists = false,
            runtimeNote = "本次启动：guest=7G，TB=1360 MiB",
            retriedWithSafeProfile = true,
        )
        assertEquals(QemuFailureKind.EarlyExit, f.kind)
        assertTrue(f.detail.contains("【启动预算】"))
        assertTrue(f.detail.contains("guest=7G"))
        assertTrue(f.detail.contains("保守档"))
    }

    @Test
    fun `unknown states keep the log tail for the log screen`() {
        val log = (1..30).joinToString("\n") { "line $it" } + "\nlast line"
        val f = QemuFailureClassifier.classify(null, log, serialLogExists = false)
        assertEquals(QemuFailureKind.Unknown, f.kind)
        assertTrue(f.logTail.endsWith("last line"))
    }

    @Test
    fun `unknown exit is no longer a vague no-known-feature message`() {
        val f = QemuFailureClassifier.classify(null, "some log", serialLogExists = false)
        // 用户点名批评的含糊话必须消失
        assertTrue(
            "不许再出现「没有匹配到已知错误特征」",
            !f.detail.contains("没有匹配到已知错误特征")
        )
        // 必须给出按链路逐层的具体排查清单
        assertTrue("要给出逐层排查", f.detail.contains("逐层排查") || f.detail.contains("固件"))
        assertTrue("要提到 payload/ram.img", f.detail.contains("payload.bin") || f.detail.contains("ram.img"))
    }

    @Test
    fun `catalog attribution makes the unknown exit concrete`() {
        val attribution = "本轮共观测 5 类错误 / 40 次命中，最高严重度=降速。最可能的阻塞点：" +
            "[root-dir-eacces] SpringBoard — 镜像根目录 0700 导致 uid 501 EACCES。处置：重编镜像。"
        val f = QemuFailureClassifier.classify(
            null, "some log", serialLogExists = true,
            catalogAttribution = attribution,
        )
        assertEquals(QemuFailureKind.Unknown, f.kind)
        assertTrue("要带上错误细化归因", f.detail.contains("启动错误细化"))
        assertTrue("要带上具体阻塞点", f.detail.contains("root-dir-eacces"))
        assertTrue("要带上处置", f.detail.contains("重编镜像"))
        assertTrue("不许再含糊", !f.detail.contains("没有匹配到已知错误特征"))
    }

    // ---- 进程被杀 / QEMU 自崩（上游 run.sh 与 Android LMK 的判据） --------------------------

    @Test
    fun `sigkill is attributed to android not qemu`() {
        val f = QemuFailureClassifier.classify(137, "", serialLogExists = true)
        assertEquals(QemuFailureKind.ProcessKilled, f.kind)
        assertTrue(f.title.contains("137"))
        assertTrue(f.detail.contains("LMK"))
        assertTrue(f.hint!!.contains("前台"))
    }

    @Test
    fun `segfault is a qemu crash with a rollback hint`() {
        val f = QemuFailureClassifier.classify(139, "qemu: fatal signal", serialLogExists = true)
        assertEquals(QemuFailureKind.QemuCrash, f.kind)
        assertTrue(f.title.contains("SIGSEGV"))
        assertTrue(f.hint!!.contains("MTTCG"))
    }

    @Test
    fun `sigabrt points at the qemu assert text`() {
        val f = QemuFailureClassifier.classify(
            134, "qemu-system-aarch64: ../../tcg/tcg.c:789: tcg_out_op: Assertion failed",
            serialLogExists = true,
        )
        assertEquals(QemuFailureKind.QemuCrash, f.kind)
        assertTrue(f.title.contains("SIGABRT"))
        assertTrue(f.hint!!.contains("assert"))
    }
}
