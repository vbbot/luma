package com.vandam.luma.data

import android.content.Context
import com.vandam.luma.BuildConfig
import java.net.URI

object ServerConfig {
    val defaultUrl: String
        get() = BuildConfig.CONVEX_URL.trim().removeSuffix("/")

    fun customUrl(context: Context): String = Prefs.getInstance(context).customServerUrl

    // A custom server always wins over the URL baked in at build time.
    // Custom servers may use plain http so self-hosted backends on a LAN work.
    fun resolveUrl(context: Context): String? {
        normalize(customUrl(context))?.let { return it }

        val deploymentUrl = defaultUrl
        if (deploymentUrl.isBlank()) {
            return null
        }
        if (!BuildConfig.DEBUG && !deploymentUrl.startsWith("https://")) {
            return null
        }
        return deploymentUrl
    }

    fun normalize(input: String): String? {
        val trimmed = input.trim().removeSuffix("/")
        if (trimmed.isEmpty()) {
            return null
        }

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            return null
        }
        if (uri.host.isNullOrBlank()) {
            return null
        }
        return trimmed
    }
}
