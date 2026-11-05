package com.android.gudao.iphoneos.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 工具箱的护栏测试。
 *
 * 目标不是"工具能跑"，而是"工具在客户端给出坏参数时拒绝得干净"：越界路径、只读模式下的写操作、
 * 危险命令、monitor 白名单、以及 delete_chain 的三道保险。这些拒绝理由会原样回到 MCP 客户端，
 * 所以它们的措辞也是行为的一部分。
 */
class ToolBoxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeBridge(
        override val runDir: File,
        override val allowWrite: Boolean,
        override val fullAccess: Boolean = false,
        // 放在 runDir 之外，避免 scope=run 的递归删除把镜像目录一起卷进去。
        val images: File = File(runDir.parentFile, runDir.name + "-images").apply { mkdirs() },
        val ipsw: File = File(runDir.parentFile, runDir.name + "-ipsw").apply { mkdirs() },
        val logs: File = File(runDir.parentFile, runDir.name + "-logs").apply { mkdirs() },
        val exports: File = File(runDir.parentFile, runDir.name + "-exports").apply { mkdirs() },
    ) : QemuBridge {
        val calls = mutableListOf<Pair<String, org.json.JSONObject>>()
        var lastHmp: String? = null

        override fun buildLogDir(): File = File(runDir, "build-logs")
        override fun chainRoots(): Map<String, File> = linkedMapOf(
            "run" to runDir, "images" to images, "ipsw" to ipsw, "logs" to logs, "exports" to exports,
        )
        override suspend fun status(): String = "QEMU: 未运行"
        override suspend fun config(): String = "smp=1"
        override suspend fun mcpInfo(): String = "server=hackios-mcp"
        override suspend fun hmp(command: String): String {
            lastHmp = command
            return "RAX=0000000000000000"
        }

        override suspend fun screen(): String = "平均亮度 12/765"
        override suspend fun dispatch(action: String, args: org.json.JSONObject): String {
            calls.add(action to args)
            return "dispatch:$action"
        }
    }

    private fun box(write: Boolean, runDir: File, full: Boolean = false): ToolBox =
        ToolBox(FakeBridge(runDir, write, full), shell = FakeShell())

    private fun names(tb: ToolBox): List<String> = (0 until tb.specs.length()).map {
        tb.specs.getJSONObject(it).getString("name")
    }

    /** 记录调用的假 shell：不真跑进程，只验证"放行/拒绝"与 root 参数是否传达。 */
    private class FakeShell : ShellRunner {
        data class Call(val command: String, val root: Boolean)

        val calls = mutableListOf<Call>()

        override fun run(command: String, cwd: File, env: Map<String, String>, timeoutSec: Int, root: Boolean): ShellResult {
            calls += Call(command, root)
            return ShellResult(0, "ok", false)
        }
    }

    private fun ToolBox.call(name: String, args: String): ToolResult = runBlocking { execute(name, args) }

    // ---- 只读工具 -------------------------------------------------------------------------------

    @Test
    fun `read_log returns the tail not the head`() {
        val dir = tmp.newFolder("run")
        File(dir, "serial.log").writeText((1..300).joinToString("\n") { "line $it" })
        val r = box(false, dir).call("read_log", """{"source":"serial","lines":50}""")
        assertTrue(r.ok)
        assertTrue(r.text.contains("line 300"))
        assertFalse(r.text.contains("line 10\n"))
        assertTrue(r.text.contains("共 300 行"))
    }

    @Test
    fun `read_log can read the app log`() {
        val dir = tmp.newFolder("run2")
        File(dir, "app.log").writeText("12:00:01.000 I/MCP: tools/call start_qemu\n12:00:02.000 E/MCP: 取帧失败\n")
        val r = box(false, dir).call("read_log", """{"source":"app"}""")
        assertTrue(r.ok)
        assertTrue(r.text.contains("tools/call start_qemu"))
        assertTrue(r.text.contains("app.log"))
    }

    @Test
    fun `errors tool is registered and reads the detailed error catalog`() {
        val dir = tmp.newFolder("run_err")
        // 工具必须在只读档也能用（排障是读操作）
        assertTrue(names(box(false, dir)).contains("errors"))
        File(dir, "error-catalog.log").writeText(
            "[err] lsd-cpu-monitor-entitlement  sev=降速 blocking=no layer=DAEMON hits=17\n" +
                "[err]   CAUSE   不是 CPU 故障：lsd 查询缺失 XPC 服务后试图 escape CPU monitor 被拒…\n" +
                "[err]   ACTION  不要降核；FAST=1 精简集已去掉大部分触发源…\n",
        )
        val r = box(false, dir).call("errors", "{}")
        assertTrue(r.ok)
        assertTrue("要给出完整根因", r.text.contains("lsd-cpu-monitor-entitlement"))
        assertTrue("要带根因", r.text.contains("CAUSE"))
        assertTrue("要带处置", r.text.contains("ACTION"))
    }

    @Test
    fun `read_log source=errors resolves the error catalog`() {
        val dir = tmp.newFolder("run_err2")
        File(dir, "error-catalog.log").writeText(
            "[err] root-dir-eacces  sev=阻塞 blocking=YES layer=KERNEL hits=1\n" +
                "[err]   ACTION  重编第 3 版镜像后即消\n",
        )
        val r = box(false, dir).call("read_log", """{"source":"errors","lines":20}""")
        assertTrue(r.ok)
        assertTrue(r.text.contains("root-dir-eacces"))
        assertTrue(r.text.contains("重编"))
    }

    @Test
    fun `read_log grep keeps only matches`() {
        val dir = tmp.newFolder("run")
        File(dir, "serial.log").writeText("boot ok\nPanic: cpu 0\nboot ok\npanic: cpu 1\n")
        val r = box(false, dir).call("read_log", """{"source":"serial","lines":10,"grep":"panic"}""")
        assertTrue(r.ok)
        assertTrue(r.text.contains("Panic: cpu 0"))
        assertTrue(r.text.contains("panic: cpu 1"))
        assertFalse(r.text.contains("boot ok"))
    }

    @Test
    fun `read_log reports a missing file instead of pretending it is empty`() {
        val dir = tmp.newFolder("run")
        val r = box(false, dir).call("read_log", """{"source":"serial"}""")
        assertFalse(r.ok)
        assertTrue(r.text.contains("不存在"))
    }

    @Test
    fun `path traversal is refused`() {
        val dir = tmp.newFolder("run")
        val r = box(false, dir).call("read_file", """{"path":"../../etc/passwd"}""")
        assertFalse(r.ok)
        assertTrue(r.text.contains("越界"))
    }

    @Test
    fun `absolute paths outside the sandbox are refused`() {
        val dir = tmp.newFolder("run")
        val r = box(false, dir).call("read_file", """{"path":"/data/data/com.android.gudao.iphoneos/shared_prefs/hackios.xml"}""")
        assertFalse(r.ok)
        assertTrue(r.text.contains("越界"))
    }

    @Test
    fun `list_files marks directories and does not recurse`() {
        val dir = tmp.newFolder("run")
        File(dir, "hackios").mkdirs()
        File(dir, "hackios/build.sh").writeText("#!/bin/sh")
        val r = box(false, dir).call("list_files", """{"path":"."}""")
        assertTrue(r.ok)
        assertTrue(r.text.contains("hackios/"))
        assertFalse(r.text.contains("build.sh"))
    }

    @Test
    fun `hmp whitelist blocks quit`() {
        val dir = tmp.newFolder("run")
        val r = box(false, dir).call("hmp", """{"command":"quit"}""")
        assertFalse(r.ok)
        assertTrue(r.text.contains("白名单"))
    }

    @Test
    fun `hmp passes info through`() {
        val dir = tmp.newFolder("run")
        val r = box(false, dir).call("hmp", """{"command":"info registers"}""")
        assertTrue(r.ok)
        assertTrue(r.text.contains("RAX"))
    }

    // ---- 权限档：工具表按档裁剪 -----------------------------------------------------------------

    @Test
    fun `read only hides every mutating tool`() {
        val dir = tmp.newFolder("run")
        val tb = box(false, dir)
        val n = names(tb)
        assertTrue(n.contains("read_log"))
        assertTrue(n.contains("inspect_chain"))
        assertTrue(n.contains("get_config"))
        for (hidden in listOf(
            "start_qemu", "stop_qemu", "restart_qemu", "set_config", "write_file",
            "run_shell", "compile_images", "download_firmware", "delete_chain",
        )) {
            assertFalse("只读档不该显示 $hidden", n.contains(hidden))
        }
        val r = tb.call("start_qemu", "{}")
        assertFalse(r.ok)
        assertTrue(r.text.contains("写权限"))
    }

    @Test
    fun `write tier exposes lifecycle and config but not delete_chain`() {
        val dir = tmp.newFolder("run")
        val n = names(box(true, dir))
        for (shown in listOf("start_qemu", "stop_qemu", "set_config", "compile_images", "write_file", "run_shell")) {
            assertTrue("读写档应显示 $shown", n.contains(shown))
        }
        assertFalse(n.contains("delete_chain"))
    }

    @Test
    fun `full access exposes delete_chain`() {
        val dir = tmp.newFolder("run")
        assertTrue(names(box(true, dir, full = true)).contains("delete_chain"))
    }

    // ---- 生命周期动作走 dispatch ----------------------------------------------------------------

    @Test
    fun `lifecycle tools dispatch to the app`() {
        val dir = tmp.newFolder("run")
        val bridge = FakeBridge(dir, allowWrite = true)
        val tb = ToolBox(bridge, shell = FakeShell())
        for (action in listOf("start_qemu", "stop_qemu", "restart_qemu", "force_cleanup", "compile_images", "deploy_assets")) {
            val r = tb.call(action, "{}")
            assertTrue("$action → ${r.text}", r.ok)
        }
        assertEquals(
            listOf("start_qemu", "stop_qemu", "restart_qemu", "force_cleanup", "compile_images", "deploy_assets"),
            bridge.calls.map { it.first },
        )
    }

    @Test
    fun `send_key validates its argument`() {
        val dir = tmp.newFolder("run")
        val tb = box(true, dir)
        assertFalse(tb.call("send_key", "{}").ok)
        assertFalse(tb.call("send_key", """{"name":"a; rm -rf /"}""").ok)
        assertTrue(tb.call("send_key", """{"name":"h"}""").ok)
    }

    // ---- 配置 -----------------------------------------------------------------------------------

    @Test
    fun `set_config rejects unknown keys and invalid values`() {
        val dir = tmp.newFolder("run")
        val tb = box(true, dir)
        assertTrue(tb.call("set_config", """{"key":"not_a_key","value":"1"}""").text.contains("未知配置项"))
        assertTrue(tb.call("set_config", """{"key":"smp","value":"9"}""").text.contains("1..8"))
        assertTrue(tb.call("set_config", """{"key":"mcp_port","value":"80"}""").text.contains("1024"))
        assertTrue(tb.call("set_config", """{"key":"theme","value":"neon"}""").text.contains("只能是"))
        // 通用入口不能绕过专用工具的参数白名单。
        val bad = tb.call("set_config", """{"key":"qemu_flags","value":"-S"}""")
        assertFalse("set_config 也必须拒绝 -S：${bad.text}", bad.ok)
    }

    @Test
    fun `set_config forwards normalized values to the app`() {
        val dir = tmp.newFolder("run")
        val bridge = FakeBridge(dir, allowWrite = true)
        val tb = ToolBox(bridge, shell = FakeShell())
        assertTrue(tb.call("set_config", """{"key":"net","value":"true"}""").ok)
        assertTrue(tb.call("set_smp", """{"cores":2}""").ok)
        assertTrue(tb.call("set_qemu_flags", """{"flags":"-d guest_errors"}""").ok)
        assertEquals(listOf("set_config", "set_config", "set_config"), bridge.calls.map { it.first })
        assertEquals("net", bridge.calls[0].second.getString("key"))
        assertEquals("true", bridge.calls[0].second.getString("value"))
        assertEquals("smp", bridge.calls[1].second.getString("key"))
        assertEquals("2", bridge.calls[1].second.getString("value"))
        assertEquals("qemu_flags", bridge.calls[2].second.getString("key"))
    }

    @Test
    fun `set_qemu_flags validates through the same gate as settings`() {
        val dir = tmp.newFolder("run")
        val tb = box(true, dir)
        assertFalse(tb.call("set_qemu_flags", """{"flags":"-S"}""").ok)
        assertTrue(tb.call("set_qemu_flags", """{"flags":"-d guest_errors"}""").ok)
    }

    // ---- 全链路盘点与删除 -----------------------------------------------------------------------

    @Test
    fun `inspect_chain lists artifacts and sizes`() {
        val run = tmp.newFolder("run")
        val bridge = FakeBridge(run, allowWrite = false)
        File(run, "payload.bin").writeText("payload")
        File(bridge.images, "system.apfs").writeText("system image")
        val r = ToolBox(bridge, shell = FakeShell()).call("inspect_chain", "{}")
        assertTrue(r.ok)
        assertTrue(r.text.contains("payload.bin"))
        assertTrue(r.text.contains("system.apfs"))
        assertTrue(r.text.contains("[images]"))
        assertTrue(r.text.contains("链路盘点"))
    }

    @Test
    fun `delete_chain is hidden when write or full access is off`() {
        val dir = tmp.newFolder("run")
        assertTrue(box(false, dir).call("delete_chain", """{"scope":"all"}""").text.contains("写权限"))
        assertTrue(box(true, dir).call("delete_chain", """{"scope":"all"}""").text.contains("完全访问"))
    }

    @Test
    fun `delete_chain rejects unknown scopes`() {
        val dir = tmp.newFolder("run")
        val r = box(true, dir, full = true).call("delete_chain", """{"scope":"everything"}""")
        assertFalse(r.ok)
        assertTrue(r.text.contains("未知 scope"))
    }

    @Test
    fun `delete_chain defaults to a dry run`() {
        val run = tmp.newFolder("run")
        val bridge = FakeBridge(run, allowWrite = true, fullAccess = true)
        val payload = File(run, "payload.bin").apply { writeText("payload") }
        val image = File(bridge.images, "system.apfs").apply { writeText("system") }
        val tb = ToolBox(bridge, shell = FakeShell())

        val r = tb.call("delete_chain", """{"scope":"all"}""")
        assertTrue(r.text, r.ok)
        assertTrue(r.text.contains("预览"))
        assertTrue(r.text.contains("scope="))
        assertTrue("dry-run 不能删文件", payload.exists())
        assertTrue("dry-run 不能删镜像", image.exists())
        assertTrue("dry-run 不该停 QEMU", bridge.calls.isEmpty())
    }

    @Test
    fun `delete_chain requires the exact confirm token`() {
        val run = tmp.newFolder("run")
        val bridge = FakeBridge(run, allowWrite = true, fullAccess = true)
        val payload = File(run, "payload.bin").apply { writeText("payload") }
        val tb = ToolBox(bridge, shell = FakeShell())

        val r = tb.call("delete_chain", """{"scope":"cache","dry_run":false,"confirm":"yes"}""")
        assertFalse(r.ok)
        assertTrue(r.text.contains("确认串不对"))
        assertTrue(payload.exists())
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun `delete_chain actually removes the whole pipeline and stops qemu first`() {
        val run = tmp.newFolder("run")
        val bridge = FakeBridge(run, allowWrite = true, fullAccess = true)
        File(run, "payload.bin").writeText("payload")
        File(run, "ram.img").writeText("ram")
        File(run, "qemu").mkdirs()
        File(run, "qemu/qemu-system-aarch64").writeText("elf")
        File(bridge.images, "system.apfs").writeText("image")
        File(bridge.ipsw, "firmware.ipsw").writeText("6gb")
        val tb = ToolBox(bridge, shell = FakeShell())

        val r = tb.call(
            "delete_chain",
            """{"scope":["run","images","firmware"],"dry_run":false,"confirm":"DELETE-CHAIN"}""",
        )
        assertTrue(r.text, r.ok)
        assertTrue(r.text.contains("已执行删除"))
        assertFalse(File(run, "payload.bin").exists())
        assertFalse(File(run, "qemu/qemu-system-aarch64").exists())
        assertFalse(File(bridge.images, "system.apfs").exists())
        assertFalse(File(bridge.ipsw, "firmware.ipsw").exists())
        // 保留目录本身：App 之后还要往里写。
        assertTrue(run.isDirectory)
        val actions = bridge.calls.map { it.first }
        assertEquals("stop_qemu", actions.first())
        assertTrue(actions.contains("refresh_artifacts"))
    }

    @Test
    fun `delete_chain cache scope keeps the qemu binary`() {
        val run = tmp.newFolder("run")
        val bridge = FakeBridge(run, allowWrite = true, fullAccess = true)
        File(run, "payload.bin").writeText("payload")
        File(run, ".payload-stamp").writeText("stamp")
        File(run, "qemu").mkdirs()
        val bin = File(run, "qemu/qemu-system-aarch64").apply { writeText("elf") }
        val tb = ToolBox(bridge, shell = FakeShell())

        val r = tb.call(
            "delete_chain",
            """{"scope":"cache","dry_run":false,"confirm":"DELETE-CHAIN"}""",
        )
        assertTrue(r.text, r.ok)
        assertFalse(File(run, "payload.bin").exists())
        assertFalse(File(run, ".payload-stamp").exists())
        assertTrue("cache 只清缓存，不该删 QEMU 二进制", bin.exists())
    }

    // ---- 文件与命令护栏 -------------------------------------------------------------------------

    @Test
    fun `write_file keeps one backup and creates parents`() {
        val dir = tmp.newFolder("run")
        val tb = box(true, dir)
        assertTrue(tb.call("write_file", """{"path":"a/b.txt","content":"one"}""").ok)
        assertTrue(tb.call("write_file", """{"path":"a/b.txt","content":"two"}""").ok)
        assertEquals("two", File(dir, "a/b.txt").readText())
        assertEquals("one", File(dir, "a/b.txt.bak").readText())
    }

    @Test
    fun `dangerous shell commands are refused before they run`() {
        val dir = tmp.newFolder("run")
        val tb = box(true, dir)
        for (bad in listOf(
            "reboot",
            "pm uninstall com.android.gudao.iphoneos",
            "settings put global airplane_mode_on 1",
            "rm -rf /sdcard/Download/hackios",
            "dd if=/dev/zero of=/dev/block/sda",
        )) {
            val r = tb.call("run_shell", """{"command":${org.json.JSONObject.quote(bad)}}""")
            assertFalse("应该拒绝：$bad", r.ok)
            assertTrue(r.text.contains("拒绝执行"))
        }
    }

    @Test
    fun `shell cannot reach outside the run directory`() {
        val dir = tmp.newFolder("run")
        val r = box(true, dir).call("run_shell", """{"command":"cat /data/data/com.android.gudao.iphoneos/shared_prefs/hackios.xml"}""")
        assertFalse(r.ok)
        assertTrue(r.text.contains("不在"))
    }

    // ---- 完全访问档 -----------------------------------------------------------------------------

    @Test
    fun `full access allows paths outside the run directory`() {
        val run = tmp.newFolder("run")
        val outside = tmp.newFolder("outside")
        val target = File(outside, "note.txt").apply { writeText("hello from android") }
        val r = box(true, run, full = true).call("read_file", """{"path":"${target.absolutePath}"}""")
        assertTrue(r.ok)
        assertTrue(r.text.contains("hello from android"))
    }

    @Test
    fun `full access keeps only the core deny list`() {
        val run = tmp.newFolder("run")
        val tb = box(true, run, full = true)
        for (bad in listOf("reboot", "dd if=/dev/zero of=/dev/block/sda", "mkfs.ext4 /dev/sda", "rm -rf /")) {
            val r = tb.call("run_shell", """{"command":${org.json.JSONObject.quote(bad)}}""")
            assertFalse("仍应拒绝：$bad", r.ok)
        }
        for (good in listOf("su -c id", "logcat -d -b crash", "pm list packages", "cat /proc/meminfo", "dumpsys battery")) {
            val r = tb.call("run_shell", """{"command":${org.json.JSONObject.quote(good)}}""")
            assertTrue("应放行：$good → ${r.text}", r.ok)
        }
    }

    @Test
    fun `run_shell forwards the root flag only in full access`() {
        val run = tmp.newFolder("run")
        val shell = FakeShell()
        val full = ToolBox(FakeBridge(run, allowWrite = true, fullAccess = true), shell = shell)
        assertTrue(runBlocking { full.execute("run_shell", """{"command":"id","root":true}""") }.ok)
        assertTrue(shell.calls.single().root)

        val plain = ToolBox(FakeBridge(run, allowWrite = true), shell = shell)
        val r = runBlocking { plain.execute("run_shell", """{"command":"id","root":true}""") }
        assertFalse(r.ok)
        assertTrue(r.text.contains("完全访问"))
    }

    @Test
    fun `full access keeps write tools hidden when write is off`() {
        val run = tmp.newFolder("run")
        val tb = ToolBox(FakeBridge(run, allowWrite = false, fullAccess = true))
        assertFalse(names(tb).contains("run_shell"))
        val r = tb.call("read_file", """{"path":"/etc/hostname"}""")
        assertFalse(r.ok)
        assertTrue(r.text.contains("越界"))
    }
}
