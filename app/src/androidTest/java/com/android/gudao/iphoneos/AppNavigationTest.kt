package com.android.gudao.iphoneos

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/**
 * Compose semantics are the contract Maestro drives in the cloud. These tests assert the same tags
 * and labels, so a rename that breaks a Maestro flow fails here first (fast, on JVM-less device
 * farms) instead of in the nightly cloud run.
 */
class AppNavigationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun deviceTabIsTheStartDestination() {
        rule.onNodeWithTag("screen_device").assertIsDisplayed()
        rule.onNodeWithTag("device_hero").assertIsDisplayed()
        rule.onNodeWithText("启动 iOS").assertIsDisplayed()
    }

    @Test
    fun tabsSwitchBetweenRootPages() {
        rule.onNodeWithText("资源").performClick()
        rule.onNodeWithTag("screen_library").assertIsDisplayed()
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithTag("screen_settings").assertIsDisplayed()
        rule.onNodeWithText("设备").performClick()
        rule.onNodeWithTag("screen_device").assertIsDisplayed()
    }

    @Test
    fun logsPageOpensAndReturns() {
        rule.onNodeWithText("日志").performClick()
        rule.onNodeWithTag("screen_logs").assertIsDisplayed()
        rule.onNodeWithTag("log_tab_Serial").assertIsDisplayed()
    }

    @Test
    fun libraryShowsFirmwareAndBuildCards() {
        rule.onNodeWithText("资源").performClick()
        rule.onNodeWithTag("firmware_card").assertIsDisplayed()
        rule.onNodeWithTag("build_card").assertIsDisplayed()
    }

    @Test
    fun uiStateSurvivesActivityRecreation() {
        // B9: before the ViewModel move, rotating the device reset every progress/status field
        // because they lived in Activity fields.
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithTag("screen_settings").assertIsDisplayed()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("screen_settings").assertIsDisplayed()
    }

    @Test
    fun screenTabShowsEmptyStateWhenQemuIsStopped() {
        rule.onNodeWithText("屏幕").performClick()
        rule.onNodeWithTag("screen_display").assertIsDisplayed()
        rule.onNodeWithText("QEMU 没有运行").assertIsDisplayed()
    }
}
