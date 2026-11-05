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
 * 0.24.0 新架构通过 MCP 管理的三个入口：boot_timeline（读计时）、set_mttcg / set_auto_fallback
 * （改多核保护）、以及 set_config 新增的两个键。验证「整条链路都能用 MCP 管理」确实覆盖到新参数。
 */
class McpArchitectureToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Bridge(override val runDir: File, override val allowWrite: Boolean) : QemuBridge {
        val calls = mutableListOf<Pair<String, org.json.JSONObject>>()
        override val fullAccess: Boolean = false
        override fun buildLogDir(): File = File(runDir, "build-logs")
        override fun chainRoots(): Map<String, File> = mapOf("run" to runDir)
        override suspend fun status(): String = "QEMU: 未运行"
        override suspend fun config(): String = "smp=6\nmttcg=true"
        override suspend fun mcpInfo(): String = "server=hackios-mcp"
        override suspend fun hmp(command: String): String = ""
        override suspend fun screen(): String = ""
        override suspend fun dispatch(action: String, args: org.json.JSONObject): String {
            calls += action to args
            return "ok:$action"
        }
    }

    private fun call(tb: ToolBox, name: String, args: String = "{}"): ToolResult = runBlocking {
        tb.execute(name, args)
    }

    @Test
    fun `boot_timeline is a read only tool available without write`() {
        val tb = ToolBox(Bridge(tmp.root, allowWrite = false))
        val names = (0 until tb.specs.length()).map { tb.specs.getJSONObject(it).getString("name") }
        assertTrue("boot_timeline 应出现在只读档", "boot_timeline" in names)
    }

    @Test
    fun `boot_timeline guides the client when no timing exists yet`() {
        val tb = ToolBox(Bridge(tmp.root, allowWrite = false))
        val r = call(tb, "boot_timeline")
        assertTrue(r.ok)
        assertTrue(r.text.contains("还没有启动计时") || r.text.contains("start_qemu"))
    }

    @Test
    fun `boot_timeline reads the timeline file and shows budgets`() {
        File(tmp.root, "boot-timeline.json").writeText(
            "logo_at_ms=80000 delta_ms=80000\ndesktop_at_ms=170000 delta_ms=90000\n" +
                "logo_verdict=true\ndesktop_verdict=true\n"
        )
        val tb = ToolBox(Bridge(tmp.root, allowWrite = false))
        val r = call(tb, "boot_timeline")
        assertTrue(r.ok)
        assertTrue(r.text.contains("logo_at_ms=80000"))
        assertTrue(r.text.contains("90000ms") || r.text.contains("180000ms"))
    }

    @Test
    fun `set_mttcg dispatches config write and requires write permission`() {
        val writeBridge = Bridge(tmp.root, allowWrite = true)
        val r = call(ToolBox(writeBridge), "set_mttcg", """{"enabled":false}""")
        assertTrue(r.ok)
        assertEquals("set_config", writeBridge.calls.last().first)
        assertEquals("mttcg", writeBridge.calls.last().second.getString("key"))
        assertEquals("false", writeBridge.calls.last().second.getString("value"))

        val ro = call(ToolBox(Bridge(tmp.root, allowWrite = false)), "set_mttcg", """{"enabled":false}""")
        assertFalse("只读档应拒绝", ro.ok)
    }

    @Test
    fun `set_auto_fallback dispatches config write`() {
        val b = Bridge(tmp.root, allowWrite = true)
        val r = call(ToolBox(b), "set_auto_fallback", """{"enabled":true}""")
        assertTrue(r.ok)
        assertEquals("auto_fallback", b.calls.last().second.getString("key"))
    }

    @Test
    fun `set_config accepts the two new keys`() {
        val b = Bridge(tmp.root, allowWrite = true)
        assertTrue(call(ToolBox(b), "set_config", """{"key":"mttcg","value":"true"}""").ok)
        assertTrue(call(ToolBox(b), "set_config", """{"key":"auto_fallback","value":"false"}""").ok)
    }

    @Test
    fun `set_config still validates the new bool keys`() {
        val b = Bridge(tmp.root, allowWrite = true)
        val r = call(ToolBox(b), "set_config", """{"key":"mttcg","value":"maybe"}""")
        assertFalse(r.ok)
        assertTrue(r.text.contains("true / false"))
    }

    @Test
    fun `set_smp default is two guest vCPUs in 0_26_0`() {
        val tb = ToolBox(Bridge(tmp.root, allowWrite = true))
        val spec = (0 until tb.specs.length())
            .map { tb.specs.getJSONObject(it) }
            .first { it.getString("name") == "set_smp" }
        val cores = spec.getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("cores")
        // 0.26.0 起 Guest vCPU 默认 2（XNU SMP bringup 最稳），六核只保留为稳定上限
        assertEquals(2, cores.getInt("default"))
        assertEquals(1, cores.getInt("minimum"))
        assertEquals(8, cores.getInt("maximum"))
        assertTrue(spec.getString("description").contains("Guest vCPU"))
    }

    @Test
    fun `set_smp rejects out of range cores`() {
        val tb = ToolBox(Bridge(tmp.root, allowWrite = true))
        assertFalse(call(tb, "set_smp", """{"cores":9}""").ok)
    }

    @Test
    fun `set_host_cores is an independent tool defaulting to eight`() {
        val b = Bridge(tmp.root, allowWrite = true)
        val tb = ToolBox(b)
        val spec = (0 until tb.specs.length())
            .map { tb.specs.getJSONObject(it) }
            .first { it.getString("name") == "set_host_cores" }
        val cores = spec.getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("cores")
        assertEquals(8, cores.getInt("default"))
        assertEquals(2, cores.getInt("minimum"))
        assertEquals(8, cores.getInt("maximum"))
        assertTrue(spec.getString("description").contains("Host compute pool"))

        // 真实调用必须写 host_cores 键，且与 smp 完全分开
        val r = call(tb, "set_host_cores", """{"cores":6}""")
        assertTrue(r.ok)
        val dispatched = b.calls.single().second
        assertEquals("set_config", b.calls.single().first)
        assertEquals("host_cores", dispatched.getString("key"))
        assertEquals("6", dispatched.getString("value"))
    }

    @Test
    fun `set_config validates the host_cores range`() {
        val tb = ToolBox(Bridge(tmp.root, allowWrite = true))
        assertTrue(call(tb, "set_config", """{"key":"host_cores","value":"6"}""").ok)
        assertFalse(call(tb, "set_config", """{"key":"host_cores","value":"1"}""").ok)
        assertFalse(call(tb, "set_config", """{"key":"host_cores","value":"9"}""").ok)
    }
}
