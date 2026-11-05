package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `clean removes ansi escapes and NUL but leaves the text`() {
        // QEMU 的 -serial file: 会把 UEFI 的清屏序列原样写进日志，导出的文件里不能带着它。
        val line = "\u001B[2J\u001B[01;01H\u001B[=3hBdsDxe: starting Boot0001"
        assertEquals("BdsDxe: starting Boot0001", LogExporter.clean(line))
        assertEquals("plain line", LogExporter.clean("plain line"))
        assertEquals("has\u0000nul".replace("\u0000", ""), LogExporter.clean("has\u0000nul"))
    }

    @Test
    fun `export writes every section and counts the lines actually written`() {
        val serial = File(tmp.root, "serial.log").apply {
            writeText("kernel boot\nlaunchd started\nSpringBoard spawned\n")
        }
        val qemu = File(tmp.root, "qemu.log").apply { writeText("qemu: -L ok\n") }
        val out = File(tmp.root, "logs/out.txt")

        val r = LogExporter.export(
            dest = out,
            header = listOf("HackiOS 完整日志", "设备：test"),
            sections = listOf(
                LogExporter.Section("串口 serial.log", serial),
                LogExporter.Section("QEMU 输出 qemu.log", qemu),
                LogExporter.Section("构建输出 缺失.log", File(tmp.root, "nope.log")),
            ),
        )

        assertEquals(4, r.lines)
        assertTrue(r.bytes > 0)
        val text = out.readText()
        assertTrue(text.startsWith("HackiOS 完整日志"))
        assertTrue(text.contains("串口 serial.log"))
        assertTrue(text.contains("launchd started"))
        assertTrue(text.contains("qemu: -L ok"))
        // 缺失的那一节仍然留下标题，说明"这里本来应该有什么"，而不是静默跳过。
        assertTrue(text.contains("构建输出 缺失.log"))
        assertTrue(text.contains("无"))
    }

    @Test
    fun `export survives a file that is being appended to`() {
        // 导出时 QEMU 往往还在写：读到的行数只要和写进去的一致即可，不能因为并发而丢整节。
        val serial = File(tmp.root, "serial.log").apply {
            writeText((1..50).joinToString("\n") { "line $it" } + "\n")
        }
        val out = File(tmp.root, "out.txt")
        val r = LogExporter.export(out, emptyList(), listOf(LogExporter.Section("串口", serial)))
        assertEquals(50, r.lines)
        assertTrue(out.readText().contains("line 50"))
    }

    @Test
    fun `a capped section keeps the tail and says how much was dropped`() {
        // 构建日志里的 4 万行文件名对排障没有用，但失败原因在最后几行 —— 所以截断必须留尾部。
        val build = File(tmp.root, "fetch.log").apply {
            writeText((1..5000).joinToString("\n") { "file $it" } + "\n")
        }
        val out = File(tmp.root, "out.txt")
        val r = LogExporter.export(
            out,
            emptyList(),
            listOf(LogExporter.Section("构建", build, maxLines = 300)),
        )
        // 只统计真正写进去的日志行；"仅保留最后 N 行"那句说明不计入。
        assertEquals(300, r.lines)
        val text = out.readText()
        assertTrue(text.contains("仅保留最后 300 行"))
        assertTrue(text.contains("file 5000"))
        assertTrue(!text.contains("file 4700"))
    }
}
