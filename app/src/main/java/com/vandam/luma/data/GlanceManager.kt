package com.vandam.luma.data

import android.util.Log
import dev.convex.android.ConvexClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.Serializable

// A line from a dashboard integration (weather, a webhook, ...), shown under
// the lock screen clock until expiresAt.
@Serializable
data class Glance(
    val glanceId: String,
    val text: String,
    val expiresAt: Double,
)

object GlanceManager {
    const val MAX_VISIBLE = 2
    private const val LOG_TAG = "Glances"
    private const val QUERY_PATH = "glances:getForDevice"

    fun observe(
        client: ConvexClient,
        accountNumber: String,
    ): Flow<List<Glance>> {
        if (!accountNumber.matches(Regex("^\\d{16}$"))) {
            return flowOf(emptyList())
        }

        return runCatching {
            client
                .subscribe<List<Glance>>(
                    name = QUERY_PATH,
                    args = mapOf("accountNumber" to accountNumber),
                ).transform { result ->
                    result.fold(
                        onSuccess = { emit(it) },
                        // Servers without integrations do not have this query.
                        onFailure = { error -> Log.w(LOG_TAG, "Glance subscription failed", error) },
                    )
                }
        }.getOrElse { error ->
            Log.w(LOG_TAG, "Unable to observe glances", error)
            emptyFlow()
        }
    }

    // The glances to show now, in the dashboard's order.
    fun visible(glances: List<Glance>): List<Glance> {
        val now = System.currentTimeMillis()
        return glances.filter { it.expiresAt > now && it.text.isNotBlank() }.take(MAX_VISIBLE)
    }
}
