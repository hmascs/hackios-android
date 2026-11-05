package com.android.gudao.iphoneos

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.android.gudao.iphoneos.state.AppViewModel
import com.android.gudao.iphoneos.ui.HackIosApp

/**
 * Single activity, single composition. The whole UI is Jetpack Compose + Glasmio; there is no XML
 * layout, no `findViewById` and no `runOnUiThread` anywhere in the app.
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything that can throw: an uncaught exception anywhere after this point leaves a
        // stack trace in run/crash.log, which the log screen can show and export. Without it a
        // "发消息就闪退" report is unanswerable -- the phone has no adb, and the only evidence dies
        // with the process.
        vm.installCrashGuard()
        // The glass material needs the content drawn edge to edge; the status bar icons follow the
        // theme automatically.
        enableEdgeToEdge()

        setContent {
            // The only runtime permission this app needs. targetSdk 28 keeps the app in legacy
            // external-storage mode, so WRITE_EXTERNAL_STORAGE is what makes Download/hackios
            // writable on Android 10..16; the ViewModel falls back to app-private storage if the
            // user declines.
            val storage = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> vm.onPermissionResult(granted) }

            HackIosApp(
                vm = vm,
                requestStorage = { storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) },
            )
        }
    }
}
