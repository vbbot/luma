package com.vandam.luma.data

import android.content.Context
import android.os.Build
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.Collator

data class ManagedApp(
    val id: String,
    val label: String,
    val packageName: String,
    val repoOwner: String = "",
    val repoName: String = "",
    val assetNamePattern: Regex = DEFAULT_ASSET_PATTERN,
    // Set for App Store apps sent by the server: install this APK directly
    // instead of looking up the repo's latest GitHub release.
    val apkUrl: String? = null,
    val versionName: String? = null,
    val sha256: String? = null,
) {
    fun toAppModel(
        context: Context,
        collator: Collator,
    ): AppModel {
        val resolvedLabel = Prefs.getInstance(context).resolveHomeItemLabel(packageName, id, label)
        return AppModel(
            appLabel = resolvedLabel,
            key = collator.getCollationKey(resolvedLabel),
            appPackage = packageName,
            appActivityName = id,
            user = android.os.Process.myUserHandle(),
            hasNotification = false,
            entryType = AppEntryType.ManagedApp,
        )
    }

    companion object {
        private val DEFAULT_ASSET_PATTERN = Regex(""".*\.apk$""", RegexOption.IGNORE_CASE)
    }
}

// An App Store app as sent by the server in DeviceSyncPayload.managedApps.
@Serializable
data class RemoteManagedApp(
    val id: String,
    val label: String,
    val packageName: String,
    val apkUrl: String,
    val versionName: String,
    val sha256: String? = null,
) {
    fun toManagedApp(): ManagedApp =
        ManagedApp(
            id = id,
            label = label,
            packageName = packageName,
            apkUrl = apkUrl,
            versionName = versionName,
            sha256 = sha256,
        )
}

data class InstalledManagedApp(
    val appId: String,
    val versionName: String,
)

object ManagedAppCatalog {
    private const val DEFAULT_REPO_OWNER = "vandamd"

    private val builtinEntries: List<ManagedApp> =
        listOf(
            ManagedApp(
                id = "buses",
                label = "Buses",
                packageName = "com.vandam.buses",
                repoOwner = DEFAULT_REPO_OWNER,
                repoName = "buses",
            ),
            ManagedApp(
                id = "passes",
                label = "Passes",
                packageName = "com.vandamd.passes",
                repoOwner = DEFAULT_REPO_OWNER,
                repoName = "passes",
            ),
            ManagedApp(
                id = "weather",
                label = "Weather",
                packageName = "com.vandam.weather",
                repoOwner = DEFAULT_REPO_OWNER,
                repoName = "weather",
            ),
            ManagedApp(
                id = "zero",
                label = "Zero",
                packageName = "com.vandam.zero",
                repoOwner = DEFAULT_REPO_OWNER,
                repoName = "zero",
            ),
            ManagedApp(
                id = "echo",
                label = "Echo",
                packageName = "com.vandam.echo",
                repoOwner = DEFAULT_REPO_OWNER,
                repoName = "echo",
            ),
            ManagedApp(
                id = "reverb",
                label = "Reverb",
                packageName = "com.vandam.reverb",
                repoOwner = DEFAULT_REPO_OWNER,
                repoName = "reverb",
            ),
        )

    private const val REMOTE_APPS_FILE = "com.vandam.luma.managed_apps"
    private const val REMOTE_APPS_KEY = "REMOTE_MANAGED_APPS"
    private val json = Json { ignoreUnknownKeys = true }

    // App Store apps the server has described, kept after they leave the
    // catalog so a removed app can still be found and uninstalled.
    @Volatile
    private var remoteEntries: List<RemoteManagedApp> = emptyList()

    val entries: List<ManagedApp>
        get() {
            val builtinIds = builtinEntries.map { it.id }.toSet()
            return builtinEntries + remoteEntries.filterNot { it.id in builtinIds }.map { it.toManagedApp() }
        }

    fun init(context: Context) {
        val stored =
            context
                .getSharedPreferences(REMOTE_APPS_FILE, Context.MODE_PRIVATE)
                .getString(REMOTE_APPS_KEY, null)
                ?: return
        remoteEntries = runCatching { json.decodeFromString<List<RemoteManagedApp>>(stored) }.getOrDefault(emptyList())
    }

    fun updateRemote(
        context: Context,
        apps: List<RemoteManagedApp>,
    ) {
        if (apps.isEmpty()) return
        val byId = linkedMapOf<String, RemoteManagedApp>()
        remoteEntries.forEach { byId[it.id] = it }
        apps
            .filter { it.id.isNotBlank() && it.packageName.isNotBlank() && it.apkUrl.startsWith("https://") }
            .forEach { byId[it.id] = it }
        val merged = byId.values.toList()
        if (merged == remoteEntries) return
        remoteEntries = merged
        context
            .getSharedPreferences(REMOTE_APPS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(REMOTE_APPS_KEY, json.encodeToString(merged))
            .apply()
    }

    fun fromId(id: String?): ManagedApp? = entries.firstOrNull { it.id == id }

    fun fromPackageName(packageName: String?): ManagedApp? = entries.firstOrNull { it.packageName == packageName }

    fun normalizeIds(enabledAppIds: List<String>): List<String> {
        val normalized = mutableListOf<String>()
        val seenIds = linkedSetOf<String>()
        enabledAppIds.forEach { appId ->
            val managedApp = fromId(appId) ?: return@forEach
            if (seenIds.add(managedApp.id)) {
                normalized.add(managedApp.id)
            }
        }
        return normalized
    }

    fun installedIds(context: Context): List<String> =
        entries
            .filter { managedApp ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.packageManager.getPackageInfo(
                            managedApp.packageName,
                            android.content.pm.PackageManager.PackageInfoFlags.of(0),
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(managedApp.packageName, 0)
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }.map(ManagedApp::id)
}
