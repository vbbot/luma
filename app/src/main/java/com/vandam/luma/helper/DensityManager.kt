package com.vandam.luma.helper

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.Display
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android has no per-app density, so switch the screen-wide one while a chosen app is in front,
 * the same way DaltonizerManager handles the colour filter.
 */
object DensityManager {
    private const val TAG = "DensityManager"
    private const val DISPLAY_DENSITY_FORCED = "display_density_forced"

    private const val PREFS_FILENAME = "com.vandam.luma.density"
    private const val PREF_DID_WE_CHANGE = "did_we_change"
    private const val PREF_ORIGINAL_DENSITY = "original_density"

    /** Smallest widths offered in settings, in dp. */
    val SMALLEST_WIDTH_OPTIONS = listOf(400, 432, 480, 540)

    private var didWeChangeDensity = false
    private var originalForcedDensity: String? = null
    private var appliedDensity = 0
    private var currentDensityApp: String? = null

    fun onAppLaunch(
        context: Context,
        packageName: String,
        appWidths: Map<String, Int>,
    ) {
        val width = appWidths[packageName] ?: return
        applyWidth(context, width)
        currentDensityApp = packageName
    }

    fun onWindowStateChanged(
        context: Context,
        packageName: String,
        appWidths: Map<String, Int>,
        isLumaTransientOverlay: Boolean = false,
    ) {
        if (packageName == context.packageName) {
            if (isLumaTransientOverlay && currentDensityApp != null) return
            restoreIfNeeded(context)
            return
        }

        if (currentDensityApp != null && DaltonizerManager.isTransientOverlay(context, packageName)) {
            return
        }

        val width = appWidths[packageName]
        if (width != null) {
            applyWidth(context, width)
            currentDensityApp = packageName
        } else {
            restoreIfNeeded(context)
        }
    }

    fun restoreIfNeeded(context: Context) {
        currentDensityApp = null
        if (!didWeChangeDensity) return
        if (setForcedDensity(context, originalForcedDensity?.toIntOrNull())) {
            Log.d(TAG, "Restored (density: ${originalForcedDensity ?: "default"})")
            didWeChangeDensity = false
            appliedDensity = 0
            originalForcedDensity = null
            clearPersistedState(context)
        }
    }

    fun recoverFromProcessDeath(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_FILENAME, 0)
        if (!prefs.getBoolean(PREF_DID_WE_CHANGE, false)) return

        val original = prefs.getString(PREF_ORIGINAL_DENSITY, null)
        if (setForcedDensity(context, original?.toIntOrNull())) {
            Log.d(TAG, "Restored after process death (density: ${original ?: "default"})")
        }
        prefs.edit().clear().apply()
        didWeChangeDensity = false
        appliedDensity = 0
        originalForcedDensity = null
        currentDensityApp = null
    }

    /** The density that gives [smallestWidthDp] on this screen. */
    fun densityForSmallestWidth(
        context: Context,
        smallestWidthDp: Int,
    ): Int = (physicalShortSidePx(context) * 160f / smallestWidthDp).roundToInt()

    private fun applyWidth(
        context: Context,
        smallestWidthDp: Int,
    ) {
        val density = densityForSmallestWidth(context, smallestWidthDp)
        if (didWeChangeDensity && appliedDensity == density) return

        if (!didWeChangeDensity) {
            originalForcedDensity =
                Settings.Secure
                    .getString(context.contentResolver, DISPLAY_DENSITY_FORCED)
                    ?.takeIf { it.isNotBlank() }
            persistState(context, originalForcedDensity)
        }

        if (setForcedDensity(context, density)) {
            didWeChangeDensity = true
            appliedDensity = density
            Log.d(TAG, "Set density $density for smallest width $smallestWidthDp")
        } else if (!didWeChangeDensity) {
            clearPersistedState(context)
        }
    }

    /** Sets the forced density, or clears it when [density] is null. */
    private fun setForcedDensity(
        context: Context,
        density: Int?,
    ): Boolean {
        val userId = Process.myUserHandle().hashCode()
        try {
            val windowManager =
                Class
                    .forName("android.view.WindowManagerGlobal")
                    .getMethod("getWindowManagerService")
                    .invoke(null) ?: throw IllegalStateException("No window manager")
            if (density == null) {
                windowManager.javaClass
                    .getMethod("clearForcedDisplayDensityForUser", Int::class.java, Int::class.java)
                    .invoke(windowManager, Display.DEFAULT_DISPLAY, userId)
            } else {
                windowManager.javaClass
                    .getMethod(
                        "setForcedDisplayDensityForUser",
                        Int::class.java,
                        Int::class.java,
                        Int::class.java,
                    ).invoke(windowManager, Display.DEFAULT_DISPLAY, density, userId)
            }
            return true
        } catch (exception: Exception) {
            Log.w(TAG, "Window manager density call failed, writing the setting instead", exception)
        }

        return try {
            Settings.Secure.putString(context.contentResolver, DISPLAY_DENSITY_FORCED, density?.toString() ?: "")
            true
        } catch (exception: SecurityException) {
            Log.e(TAG, "No permission to change the display density", exception)
            false
        }
    }

    private fun physicalShortSidePx(context: Context): Int {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        return min(mode.physicalWidth, mode.physicalHeight)
    }

    private fun persistState(
        context: Context,
        originalDensity: String?,
    ) {
        context
            .getSharedPreferences(PREFS_FILENAME, 0)
            .edit()
            .putBoolean(PREF_DID_WE_CHANGE, true)
            .putString(PREF_ORIGINAL_DENSITY, originalDensity)
            .commit()
    }

    private fun clearPersistedState(context: Context) {
        context.getSharedPreferences(PREFS_FILENAME, 0).edit().clear().commit()
    }
}
