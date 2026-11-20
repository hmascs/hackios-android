package com.android.gudao.iphoneos

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import com.android.gudao.iphoneos.engine.GpuIdentity

/**
 * 读宿主的真实 GPU 型号。
 *
 * 只认 `GL_RENDERER`：驱动自己报出来的字符串（"Adreno (TM) 830"），而不是从 SoC 名字猜。
 * 整个过程限制在几毫秒内，失败一律返回「未识别」，不抛异常——识别不出来不该让 App 起不来。
 */
internal object GpuProbe {

    fun query(): GpuIdentity {
        val pair = runCatching { eglStrings() }.getOrNull()
        if (pair != null && pair.first.isNotBlank()) {
            return GpuIdentity.of(pair.first, pair.second, GpuIdentity.Source.Egl)
        }
        val hint = runCatching {
            listOfNotNull(
                Build.SOC_MODEL.takeIf { it.isNotBlank() },
                Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        }.getOrDefault("")
        return GpuIdentity.of(
            "",
            "",
            GpuIdentity.Source.Unavailable,
            hint.ifBlank { "EGL 查询失败" },
        )
    }

    /** 一个 1×1 的 pbuffer + GLES2 上下文，只为了拿两个字符串，随后立即释放。 */
    private fun eglStrings(): Pair<String, String>? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        var context: EGLContext? = null
        var surface: EGLSurface? = null
        try {
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0)) return null
            val config = configs.firstOrNull() ?: return null
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return null
            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            if (surface == EGL14.EGL_NO_SURFACE) return null
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty()
            return renderer to vendor
        } finally {
            // 顺序固定：先解绑，再销毁 surface/context，最后 terminate display。
            if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
            }
            if (context != null && context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}
