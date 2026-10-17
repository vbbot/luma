package com.vandam.luma.helper

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vandam.luma.LumaApplication
import com.vandam.luma.R
import com.vandam.luma.data.CardNotificationSpec
import com.vandam.luma.data.LockscreenCard
import com.vandam.luma.data.LockscreenCards
import com.vandam.luma.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// Phone notifications that lock screen cards ask for (e.g. Habitica
// reminders), for every integration alike. Each card update is synced into
// exact alarms, so they fire with the screen off and offline; at the time,
// IntegrationNotificationReceiver posts the notification (checking first with
// the server when the card asks). Format: dashboard docs/lockscreen-cards.md.
object IntegrationNotifications {
    private const val LOG_TAG = "IntegrationNotifications"
    private const val CHANNEL_ID = "integrations"
    private const val STATE_FILE = "com.vandam.luma.integration_notifications"
    private const val STATE_SCHEDULED = "scheduled"
    private const val STATE_POSTED = "posted"
    private const val CHECK_TIMEOUT_MS = 6_000L

    const val ACTION_FIRE = "com.vandam.luma.INTEGRATION_NOTIFICATION_FIRE"
    const val ACTION_OPEN = "com.vandam.luma.INTEGRATION_NOTIFICATION_OPEN"
    const val EXTRA_ENTRY = "entry"

    private val json = Json { ignoreUnknownKeys = true }

    // One scheduled or posted notification. key = "cardId/notificationId".
    @Serializable
    data class Entry(
        val key: String,
        val cardId: String,
        val notificationId: String,
        val at: Long,
        val title: String,
        val text: String? = null,
        val pageId: String? = null,
        val until: Long? = null,
        val checkFirst: Boolean = false,
    )

    private fun entry(
        card: LockscreenCard,
        spec: CardNotificationSpec,
    ) = Entry(
        key = "${card.id}/${spec.id}",
        cardId = card.id,
        notificationId = spec.id,
        at = spec.at,
        title = spec.title,
        text = spec.text,
        pageId = spec.pageId,
        until = spec.until,
        checkFirst = spec.checkFirst,
    )

    // Brings alarms and posted notifications in line with the cards: schedules
    // new and changed ones, cancels those no longer listed, and takes down a
    // posted notification once its card stops listing it (e.g. the task was
    // done).
    fun sync(
        context: Context,
        cards: List<LockscreenCard>,
    ) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val enabled = Prefs.getInstance(appContext).integrationNotifications
        val listed =
            if (enabled) {
                cards.filter { it.expiresAt > now }.flatMap { card -> card.notifications.map { entry(card, it) } }
            } else {
                emptyList()
            }
        val wanted = listed.filter { it.at > now }.associateBy { it.key }
        val listedKeys = listed.map { it.key }.toSet()

        val previous = load(appContext, STATE_SCHEDULED).associateBy { it.key }
        for ((key, old) in previous) {
            if (wanted[key] != old) cancelAlarm(appContext, old)
        }
        for ((key, entry) in wanted) {
            if (previous[key] != entry) scheduleAlarm(appContext, entry)
        }
        save(appContext, STATE_SCHEDULED, wanted.values.toList())

        val posted = load(appContext, STATE_POSTED)
        val stillPosted = posted.filter { it.key in listedKeys && (it.until == null || it.until > now) }
        for (gone in posted - stillPosted.toSet()) notificationManager(appContext).cancel(gone.key, 0)
        if (stillPosted.size != posted.size) save(appContext, STATE_POSTED, stillPosted)
    }

    // At an alarm: post it, unless the card asks to check first and the
    // server's current card no longer lists it.
    internal fun fire(
        context: Context,
        entry: Entry,
        done: () -> Unit,
    ) {
        val appContext = context.applicationContext
        save(appContext, STATE_SCHEDULED, load(appContext, STATE_SCHEDULED).filterNot { it.key == entry.key })
        if (!Prefs.getInstance(appContext).integrationNotifications) return done()
        if (entry.until != null && entry.until <= System.currentTimeMillis()) return done()
        if (!entry.checkFirst) {
            post(appContext, entry)
            return done()
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val stillListed = stillListed(appContext, entry)
                // Unknown (offline): notify anyway rather than miss it.
                if (stillListed != false) post(appContext, entry)
            } finally {
                done()
            }
        }
    }

    // true/false from the server's current card, null if it could not be asked.
    private suspend fun stillListed(
        context: Context,
        entry: Entry,
    ): Boolean? {
        val accountNumber = Prefs.getInstance(context).accountNumber
        val application = context as? LumaApplication ?: return null
        val client = application.createConvexClient() ?: return null
        return try {
            val update =
                withTimeoutOrNull(CHECK_TIMEOUT_MS) { LockscreenCards.observe(client, accountNumber).first() }
                    ?: return null
            LockscreenCards.saveCache(context, accountNumber, update.json)
            update.cards.any { card -> card.id == entry.cardId && card.notifications.any { it.id == entry.notificationId } }
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Could not check a notification with the server", error)
            null
        } finally {
            application.closeConvexClient(client)
        }
    }

    private fun post(
        context: Context,
        entry: Entry,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(LOG_TAG, "Notification permission not granted; skipping ${entry.key}")
            return
        }
        ensureChannel(context)
        val open =
            PendingIntent.getBroadcast(
                context,
                entry.key.hashCode(),
                Intent(context, IntegrationNotificationReceiver::class.java)
                    .setAction(ACTION_OPEN)
                    .putExtra(EXTRA_ENTRY, json.encodeToString(Entry.serializer(), entry)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.filled_circle)
                .setContentTitle(entry.title)
                .setContentText(entry.text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .apply { entry.until?.let { setTimeoutAfter((it - System.currentTimeMillis()).coerceAtLeast(1)) } }
                .build()
        notificationManager(context).notify(entry.key, 0, notification)
        save(context, STATE_POSTED, load(context, STATE_POSTED).filterNot { it.key == entry.key } + entry)
    }

    private fun ensureChannel(context: Context) {
        val manager = notificationManager(context)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_integrations),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    private fun alarmIntent(
        context: Context,
        entry: Entry,
    ) = PendingIntent.getBroadcast(
        context,
        entry.key.hashCode(),
        Intent(context, IntegrationNotificationReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ENTRY, json.encodeToString(Entry.serializer(), entry)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scheduleAlarm(
        context: Context,
        entry: Entry,
    ) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = alarmIntent(context, entry)
        // Exact where allowed (Luma declares USE_EXACT_ALARM); otherwise within
        // a few minutes is still useful.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.at, intent)
        }
    }

    private fun cancelAlarm(
        context: Context,
        entry: Entry,
    ) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, entry))
    }

    private fun notificationManager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun load(
        context: Context,
        key: String,
    ): List<Entry> {
        val stored = context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE).getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Entry.serializer()), stored) }.getOrDefault(emptyList())
    }

    private fun save(
        context: Context,
        key: String,
        entries: List<Entry>,
    ) {
        context
            .getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(key, json.encodeToString(ListSerializer(Entry.serializer()), entries))
            .apply()
    }

    internal fun decode(intent: Intent): Entry? =
        intent.getStringExtra(EXTRA_ENTRY)?.let { runCatching { json.decodeFromString(Entry.serializer(), it) }.getOrNull() }
}

// Alarms (ACTION_FIRE) and taps on posted notifications (ACTION_OPEN).
class IntegrationNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val entry = IntegrationNotifications.decode(intent) ?: return
        when (intent.action) {
            IntegrationNotifications.ACTION_FIRE -> {
                val pending = goAsync()
                IntegrationNotifications.fire(context, entry) { pending.finish() }
            }
            // Open the lock screen on the card (and page) it came from.
            IntegrationNotifications.ACTION_OPEN -> ActionService.instance()?.showCardOnLockScreen(entry.cardId, entry.pageId)
        }
    }
}
