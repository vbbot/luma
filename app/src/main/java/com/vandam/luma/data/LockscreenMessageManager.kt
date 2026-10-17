package com.vandam.luma.data

import android.util.Log
import dev.convex.android.ConvexClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.Serializable

@Serializable
data class LockscreenMessage(
    val messageId: String,
    val text: String,
    val expiresAt: Double,
)

object LockscreenMessageManager {
    // The last message, kept like the cards (LockscreenCards.saveCache) so it
    // shows the moment the lock screen appears.
    private const val CACHE_FILE = "com.vandam.luma.lockscreen_cache"
    private const val CACHE_ACCOUNT = "message_account"
    private const val CACHE_MESSAGE = "message"
    private val cacheJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun saveCache(
        context: android.content.Context,
        accountNumber: String,
        message: LockscreenMessage?,
    ) {
        context
            .getSharedPreferences(CACHE_FILE, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(CACHE_ACCOUNT, accountNumber)
            .putString(CACHE_MESSAGE, message?.let { cacheJson.encodeToString(LockscreenMessage.serializer(), it) })
            .apply()
    }

    fun loadCache(
        context: android.content.Context,
        accountNumber: String,
    ): LockscreenMessage? {
        val prefs = context.getSharedPreferences(CACHE_FILE, android.content.Context.MODE_PRIVATE)
        if (prefs.getString(CACHE_ACCOUNT, null) != accountNumber) return null
        val json = prefs.getString(CACHE_MESSAGE, null) ?: return null
        return runCatching { cacheJson.decodeFromString(LockscreenMessage.serializer(), json) }.getOrNull()
    }

    private const val LOG_TAG = "LockscreenMessage"
    private const val QUERY_PATH = "lockscreenMessages:getCurrentForDevice"

    fun observe(
        client: ConvexClient,
        accountNumber: String,
    ): Flow<LockscreenMessage?> {
        if (!accountNumber.matches(Regex("^\\d{16}$"))) {
            return flowOf(null)
        }

        return runCatching {
            client
                .subscribe<LockscreenMessage?>(
                    name = QUERY_PATH,
                    args = mapOf("accountNumber" to accountNumber),
                ).transform { result ->
                    result.fold(
                        onSuccess = { emit(it) },
                        onFailure = { error ->
                            Log.w(LOG_TAG, "Lock-screen message subscription failed", error)
                        },
                    )
                }
        }.getOrElse { error ->
            Log.w(LOG_TAG, "Unable to observe lock-screen messages", error)
            emptyFlow()
        }
    }
}
