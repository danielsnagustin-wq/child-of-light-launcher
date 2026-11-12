package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuVendor

/**
 * Detección real de GPU al primer arranque.
 *
 * Método: contexto EGL offscreen (pbuffer 1x1) + GLES20.glGetString, que es
 * la forma estándar de leer el renderer sin NDK ni Vulkan. No se inventa:
 * si EGL falla, el resultado es UNKNOWN con renderer "No detectado".
 *
 * SoC: Build.SOC_MODEL (API 31+) o Build.HARDWARE.
 * Vulkan: system features android.hardware.vulkan.version / .level.
 * La versión Vulkan se estima por feature: el feature "version" se consulta
 * con la versión codificada VK_MAKE_VERSION (major<<22 | minor<<12 | patch).
 * Es una estimación, y así se etiqueta en la UI ("1.1+", no un número exacto).
 */
object GpuDetector {

    fun detect(context: Context): GpuInfo {
        val gles = readGlesStrings()
        val (renderer, vendor, glesVersion) = Triple(gles.renderer, gles.vendor, gles.version)
        val soc = socModel()
        val vulkanSupported = hasVulkan(context)
        val vulkanLabel = vulkanVersionLabel(context)
        val detectedVendor = classify(renderer, vendor, soc)
        return GpuInfo(
            vendor = detectedVendor,
            renderer = renderer.ifBlank { "No detectado" },
            vendorString = vendor,
            glesVersion = glesVersion,
            socModel = soc,
            vulkanVersionLabel = vulkanLabel,
            vulkanSupported = vulkanSupported,
            maxAnisotropy = gles.maxAnisotropy,
            detectedAtMillis = System.currentTimeMillis(),
        )
    }

    fun classify(renderer: String, vendor: String, socModel: String = ""): GpuVendor {
        val r = renderer.uppercase()
        val v = vendor.uppercase()
        val soc = socModel.uppercase()
        return when {
            r.contains("ADRENO") || v.contains("QUALCOMM") -> GpuVendor.ADRENO
            // M24: antes bastaba vendor.contains("ARM") para ser Mali; cualquier
            // GPU ARM no-Mali se clasificaba mal. Ahora solo el renderer manda
            // (Mali/Immortalis); un vendor ARM con otro renderer es OTHER.
            r.contains("MALI") || r.contains("IMMORTALIS") -> GpuVendor.MALI
            // Xclipse (Samsung/AMD RDNA, Exynos 2200/2400/1480/2600;
            // ronda 0.2.64, H-02): renderer "Samsung Xclipse 920/940"
            // (confirmado por el workaround de Chromium) o SoC Exynos
            // como respaldo si algún driver reporta otro renderer. La
            // rama va DESPUÉS de Mali: un Exynos con Mali (p. ej. 2100,
            // Mali-G78) sigue siendo Mali por su renderer.
            r.contains("XCLIPSE") || soc.contains("EXYNOS") -> GpuVendor.XCLIPSE
            // PowerVR (Imagination): renderer "PowerVR ..." o vendor
            // "Imagination Technologies". Sin perfil propio: cae en el
            // perfil Genérico seguro (defaultProfileFor).
            r.contains("POWERVR") || v.contains("IMAGINATION") -> GpuVendor.POWERVR
            // Apple, emuladores y resto: OTHER -> perfil Genérico
            // seguro. No se inventa un perfil para ellos.
            r.isBlank() -> GpuVendor.UNKNOWN
            else -> GpuVendor.OTHER
        }
    }

    fun socModel(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL ?: Build.HARDWARE ?: ""
            } else {
                Build.HARDWARE ?: ""
            }
        } catch (_: Exception) {
            Build.HARDWARE ?: ""
        }
    }

    private fun hasVulkan(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
                pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
        } catch (_: Exception) {
            false
        }
    }

    private fun vulkanVersionLabel(context: Context): String {
        return try {
            val pm = context.packageManager
            // VK_MAKE_VERSION(1,3,0)=0x403000, (1,2,0)=0x402000, (1,1,0)=0x401000, (1,0,0)=0x400000
            when {
                pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x403000) -> "Vulkan 1.3+"
                pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x402000) -> "Vulkan 1.2+"
                pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000) -> "Vulkan 1.1+"
                pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x400000) -> "Vulkan 1.0+"
                hasVulkan(context) -> "Vulkan (versión no determinada)"
                else -> "Sin Vulkan"
            }
        } catch (_: Exception) {
            "Desconocido"
        }
    }

    /** Strings GLES + anisotrópico máximo, leídos en un solo contexto EGL. */
    private data class GlesStrings(
        val renderer: String,
        val vendor: String,
        val version: String,
        val maxAnisotropy: Int?,
    )

    /**
     * Lee GL_RENDERER / GL_VENDOR / GL_VERSION con un contexto EGL pbuffer.
     * Devuelve strings vacíos si no se puede crear el contexto.
     *
     * Anisotrópico máximo (ronda 0.2.64, P2-2 Mali): GL_MAX_TEXTURE_
     * MAX_ANISOTROPY_EXT (0x84FF), solo si el driver anuncia
     * GL_EXT_texture_filter_anisotropic. Es el límite del muestreador
     * del mismo silicio que el límite Vulkan (maxSamplerAnisotropy,
     * renderer.cpp:1647-1650): el pin NO lo expone por JNI, así que
     * esta lectura GLES es el tope que el launcher puede conocer sin
     * tocar el pin. null si no se puede leer: sin él no hay clamp.
     */
    private fun readGlesStrings(): GlesStrings {
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return GlesStrings("", "", "", null)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return GlesStrings("", "", "", null)
            val attribList = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] == 0 || configs[0] == null
            ) return GlesStrings("", "", "", null)
            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE,
            )
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], pbufferAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return GlesStrings("", "", "", null)
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return GlesStrings("", "", "", null)
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return GlesStrings("", "", "", null)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: ""
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: ""
            val versionStr = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
            val maxAnisotropy = readMaxAnisotropyFromGl()
            return GlesStrings(renderer, vendor, versionStr, maxAnisotropy)
        } catch (_: Exception) {
            return GlesStrings("", "", "", null)
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (_: Exception) {
                // Limpieza best-effort; la detección ya devolvió su resultado.
            }
        }
    }

    /**
     * GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT (0x84FF) con el contexto EGL
     * ya activo (solo la llama readGlesStrings). Sin la extensión
     * GL_EXT_texture_filter_anisotropic no hay anisotrópico que
     * limitar: null. El valor se trunca a entero (el driver devuelve
     * float, p. ej. 16.0) y se limita a 1..16, que es lo que ofrece
     * la UI: un valor mayor no cambia nada visible.
     */
    private fun readMaxAnisotropyFromGl(): Int? {
        return try {
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
            if (!extensions.contains("GL_EXT_texture_filter_anisotropic")) return null
            val out = FloatArray(1)
            GLES20.glGetFloatv(0x84FF, out, 0)
            val max = out[0].toInt()
            if (max < 1) null else minOf(max, 16)
        } catch (_: Exception) {
            null
        }
    }
}
