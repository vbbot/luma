package com.vandam.luma.helper

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import com.vandam.luma.data.Prefs

/**
 * Android turns on Do Not Disturb whenever the ringer goes to silent, which LightOS's silent switch
 * and the volume keys do. Luma keeps silent without DND instead: the ringer sits on vibrate with the
 * ring and notification volumes at 0 and their vibration turned off, and Luma shows it as silent.
 */
object SilentModeManager {
    private const val TAG = "SilentModeManager"
    private const val RING_VIBRATION_INTENSITY = "ring_vibration_intensity"
    private const val NOTIFICATION_VIBRATION_INTENSITY = "notification_vibration_intensity"
    private const val VIBRATION_INTENSITY_OFF = 0

    private const val PREFS_FILENAME = "com.vandam.luma.silent"
    private const val PREF_ACTIVE = "active"
    private const val PREF_RING_VOLUME = "ring_volume"
    private const val PREF_NOTIFICATION_VOLUME = "notification_volume"
    private const val PREF_RING_VIBRATION = "ring_vibration"
    private const val PREF_NOTIFICATION_VIBRATION = "notification_vibration"

    fun isActive(context: Context): Boolean = prefs(context).getBoolean(PREF_ACTIVE, false)

    private fun isEnabled(context: Context): Boolean = Prefs.getInstance(context).silentWithoutDnd

    /** The ringer mode as the user sees it: Luma's silent state counts as silent. */
    fun effectiveRingerMode(context: Context): Int {
        val ringerMode = audioManager(context).ringerMode
        return if (ringerMode == AudioManager.RINGER_MODE_VIBRATE && isActive(context)) {
            AudioManager.RINGER_MODE_SILENT
        } else {
            ringerMode
        }
    }

    /** Sets the ringer mode, using Luma's silent state in place of Android's. */
    fun setRingerMode(
        context: Context,
        mode: Int,
    ) {
        when (mode) {
            AudioManager.RINGER_MODE_SILENT -> {
                if (isEnabled(context)) enter(context) else audioManager(context).ringerMode = mode
            }

            else -> {
                if (isActive(context)) leave(context, mode)
                audioManager(context).ringerMode = mode
            }
        }
    }

    /** Called when the ringer mode changes, from anywhere (LightOS, the volume keys, Luma). */
    fun onRingerModeChanged(context: Context) {
        if (!isEnabled(context)) return
        when (audioManager(context).ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> enter(context)
            AudioManager.RINGER_MODE_NORMAL -> if (isActive(context)) leave(context, AudioManager.RINGER_MODE_NORMAL)
        }
    }

    fun recoverOnStart(context: Context) {
        if (!isEnabled(context) && isActive(context)) {
            onSettingChanged(context)
            return
        }
        onRingerModeChanged(context)
    }

    /** Switches between Luma's silent and Android's when the setting changes while silent. */
    fun onSettingChanged(context: Context) {
        if (isEnabled(context)) {
            onRingerModeChanged(context)
        } else if (isActive(context)) {
            leave(context, AudioManager.RINGER_MODE_SILENT)
            try {
                audioManager(context).ringerMode = AudioManager.RINGER_MODE_SILENT
            } catch (exception: SecurityException) {
                Log.e(TAG, "No permission to change the ringer", exception)
            }
        }
    }

    private fun enter(context: Context) {
        val audioManager = audioManager(context)
        val prefs = prefs(context)
        if (!prefs.getBoolean(PREF_ACTIVE, false)) {
            prefs
                .edit()
                .putBoolean(PREF_ACTIVE, true)
                .putInt(PREF_RING_VOLUME, audioManager.getStreamVolume(AudioManager.STREAM_RING))
                .putInt(PREF_NOTIFICATION_VOLUME, audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
                .putString(PREF_RING_VIBRATION, readSystemSetting(context, RING_VIBRATION_INTENSITY))
                .putString(PREF_NOTIFICATION_VIBRATION, readSystemSetting(context, NOTIFICATION_VIBRATION_INTENSITY))
                .commit()
            Log.i(TAG, "Entering silent without Do Not Disturb")
        }

        writeSystemSetting(context, RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF.toString())
        writeSystemSetting(context, NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF.toString())
        try {
            // Leaving silent for vibrate also makes Android turn off the DND it linked to silent.
            audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        } catch (exception: SecurityException) {
            Log.e(TAG, "No permission to change the ringer", exception)
        }
        turnOffDoNotDisturb(context)
    }

    private fun leave(
        context: Context,
        mode: Int,
    ) {
        val prefs = prefs(context)
        val ringVolume = prefs.getInt(PREF_RING_VOLUME, 1).coerceAtLeast(1)
        val notificationVolume = prefs.getInt(PREF_NOTIFICATION_VOLUME, 1).coerceAtLeast(1)
        writeSystemSetting(context, RING_VIBRATION_INTENSITY, prefs.getString(PREF_RING_VIBRATION, null))
        writeSystemSetting(context, NOTIFICATION_VIBRATION_INTENSITY, prefs.getString(PREF_NOTIFICATION_VIBRATION, null))
        prefs.edit().clear().commit()
        Log.i(TAG, "Leaving silent")

        if (mode != AudioManager.RINGER_MODE_NORMAL) return
        val audioManager = audioManager(context)
        try {
            if (audioManager.getStreamVolume(AudioManager.STREAM_RING) == 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_RING, ringVolume, 0)
            }
            if (audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, notificationVolume, 0)
            }
        } catch (exception: SecurityException) {
            Log.e(TAG, "No permission to restore the ring volume", exception)
        }
    }

    private fun turnOffDoNotDisturb(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        if (notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) return
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            Log.w(TAG, "No Do Not Disturb access; leaving it on")
            return
        }
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        Log.i(TAG, "Turned off Do Not Disturb")
    }

    private fun readSystemSetting(
        context: Context,
        name: String,
    ): String? = Settings.System.getString(context.contentResolver, name)

    private fun writeSystemSetting(
        context: Context,
        name: String,
        value: String?,
    ) {
        try {
            Settings.System.putString(context.contentResolver, name, value)
        } catch (exception: Exception) {
            Log.e(TAG, "Can't change $name", exception)
        }
    }

    private fun audioManager(context: Context): AudioManager = context.getSystemService(AudioManager::class.java)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILENAME, 0)
}
