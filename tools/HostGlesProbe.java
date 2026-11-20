import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import java.nio.ByteBuffer;

/** Standalone Android-side smoke test. Does not modify the guest or install a driver. */
public final class HostGlesProbe {
    public static void main(String[] args) {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        require(display != EGL14.EGL_NO_DISPLAY, "eglGetDisplay");
        require(EGL14.eglInitialize(display, new int[2], 0, new int[2], 0), "eglInitialize");
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            int[] attrs = { EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE };
            require(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0,
                "eglChooseConfig ES3");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE }, 0);
            require(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext");
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                new int[] { EGL14.EGL_WIDTH, 4, EGL14.EGL_HEIGHT, 4, EGL14.EGL_NONE }, 0);
            require(surface != EGL14.EGL_NO_SURFACE, "eglCreatePbufferSurface");
            require(EGL14.eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent");
            String renderer = GLES30.glGetString(GLES30.GL_RENDERER);
            GLES30.glClearColor(1, 0, 1, 1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            ByteBuffer rgba = ByteBuffer.allocateDirect(4);
            GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba);
            int gl = GLES30.glGetError();
            if (renderer == null || gl != GLES30.GL_NO_ERROR || rgba.get(0) != (byte)255 || rgba.get(1) != 0
                || rgba.get(2) != (byte)255 || rgba.get(3) != (byte)255) {
                throw new IllegalStateException("GLES pixel verification failed (error=" + gl + ")");
            }
            System.out.println("host GLES renderer=" + renderer);
            System.out.println("host GLES version=" + GLES30.glGetString(GLES30.GL_VERSION));
            System.out.println("host GLES offscreen pixel verified; no iOS acceleration implied");
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }
    private static void require(boolean ok, String stage) {
        if (!ok) throw new IllegalStateException(stage + ": EGL 0x" + Integer.toHexString(EGL14.eglGetError()));
    }
}
