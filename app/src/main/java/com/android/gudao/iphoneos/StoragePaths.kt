package com.android.gudao.iphoneos

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Where the firmware and the built images live.
 *
 * The requirement is that the IPSW is kept **permanently** in the public Downloads directory
 * (`Download/hackios/ipsw`) and that the images the phone builds are published next to it
 * (`Download/hackios/images`), instead of disappearing with the app's private storage.
 *
 * Writing there needs "All files access" (MANAGE_EXTERNAL_STORAGE) on Android 11+; scoped
 * storage otherwise only allows MediaStore/SAF. This app is sideloaded and asks for that
 * permission explicitly (see MainActivity.requestStoragePermission). When the user refuses,
 * everything falls back to the app's own external directory
 * (`/sdcard/Android/data/<pkg>/files/hackios/...`), which needs no permission at all: the app
 * keeps working, the files are just harder to reach from a file manager.
 */
object StoragePaths {

    private const val TAG = "StoragePaths"

    /** True when the app may write into shared storage (Download/...) directly. */
    fun hasAllFilesAccess(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            // Pre-Android 11: the legacy WRITE_EXTERNAL_STORAGE grant is enough; it is requested
            // at runtime by MainActivity on those versions.
            return true
        }
        if (Environment.isExternalStorageManager()) return true
        // Android 11+ honours requestLegacyExternalStorage for apps that target API 29 or lower.
        // This APK targets 28 (see app/build.gradle.kts for why), so it stays in legacy mode and
        // a plain WRITE_EXTERNAL_STORAGE grant is enough to write Download/hackios/... -- no
        // "all files access" toggle required. Without this branch the app would needlessly fall
        // back to /sdcard/Android/data/<pkg>/files and the firmware would not be "permanently
        // stored in Download/hackios/ipsw" as required.
        val target = context.applicationInfo.targetSdkVersion
        if (target <= android.os.Build.VERSION_CODES.Q) {
            return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return false
    }

    /** The public root: /sdcard/Download/hackios, or null when it is not usable. */
    fun publicRoot(context: Context): File? {
        if (!hasAllFilesAccess(context)) return null
        return try {
            val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Constants.PUBLIC_HACKIOS_DIR)
            f.mkdirs()
            if (f.canWrite()) f else null
        } catch (t: Throwable) {
            Log.w(TAG, "public root unavailable: ${t.message}")
            null
        }
    }

    /** The app's own fallback root (always writable, no permission needed). */
    fun privateRoot(context: Context): File =
        File(context.getExternalFilesDir(null), Constants.PUBLIC_HACKIOS_DIR).apply { mkdirs() }

    /** Root that is actually in use: public when possible, private otherwise. */
    fun root(context: Context): File = publicRoot(context) ?: privateRoot(context)

    /** Whether the IPSW currently lives in the public directory (as the user asked for). */
    fun isPublic(context: Context): Boolean = publicRoot(context) != null

    /** Download/hackios/ipsw (or the private fallback). */
    fun ipswDir(context: Context): File = File(root(context), Constants.PUBLIC_IPSW_SUBDIR).apply { mkdirs() }

    /** Download/hackios/images (or the private fallback). */
    fun imagesDir(context: Context): File = File(root(context), Constants.PUBLIC_IMAGES_SUBDIR).apply { mkdirs() }

    /** The IPSW as it is expected in ipswDir. */
    fun ipswFile(context: Context): File = File(ipswDir(context), Constants.IPSW_FILENAME)

    /** The images as they are published in imagesDir. */
    fun systemApfs(context: Context): File = File(imagesDir(context), Constants.IMG_SYSTEM)
    fun rootApfs(context: Context): File = File(imagesDir(context), Constants.IMG_ROOT)
}
