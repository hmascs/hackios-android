package com.android.gudao.iphoneos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.29.2 真机踩过的坑：`-netdev user,id=net0,dns=192.168.1.1,114.114.114.114` 会被 QEMU 按逗号
 * 切分，第二个地址变成独立选项名 → "Invalid parameter" → 机器初始化阶段 exit=1、串口都没开。
 */
class UserNetdevArgTest {

    @Test
    fun only_the_first_dns_is_emitted_so_qemu_does_not_split_it() {
        val arg = userNetdevArg(listOf("192.168.1.1", "114.114.114.114"))
        assertEquals("user,id=net0,dns=192.168.1.1", arg)
        assertFalse("第二个 DNS 不能出现在参数里", arg.contains("114.114.114.114"))
    }

    @Test
    fun blank_and_malformed_entries_are_skipped() {
        assertEquals("user,id=net0,dns=8.8.8.8", userNetdevArg(listOf("  ", "8.8.8.8")))
        // 带逗号的条目无法安全表达，直接丢弃，避免又拼出 QEMU 会切分的参数。
        assertEquals("user,id=net0", userNetdevArg(listOf("8.8.8.8,1.1.1.1")))
    }

    @Test
    fun no_dns_still_produces_a_valid_netdev() {
        assertTrue(userNetdevArg(emptyList()).startsWith("user,id=net0"))
    }
}
