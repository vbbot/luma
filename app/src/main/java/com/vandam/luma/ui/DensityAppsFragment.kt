package com.vandam.luma.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.vandam.luma.R
import com.vandam.luma.data.HomeItemsManager
import com.vandam.luma.data.Prefs
import com.vandam.luma.data.Tool
import com.vandam.luma.ui.compose.MessageText
import com.vandam.luma.ui.compose.SelectorButton
import com.vandam.luma.ui.compose.SettingsScreen

class DensityAppsFragment : Fragment() {
    private val prefs by lazy { Prefs.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = composeView(onSwipeBack = ::goBack) { Screen() }

    @Composable
    private fun Screen() {
        val context = requireContext()
        val densityApps = remember { prefs.densityApps }
        val items =
            remember {
                HomeItemsManager
                    .orderedEnabledItems(context, prefs)
                    .filter { !it.appPackage.startsWith(Tool.SYNTHETIC_PACKAGE_PREFIX) }
                    .distinctBy { it.appPackage }
                    .sortedBy { it.displayName }
            }
        val hasPermission =
            remember {
                context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED
            }

        SettingsScreen(
            title = stringResource(R.string.settings_density_apps),
            onBack = ::goBack,
        ) {
            if (!hasPermission) {
                MessageText(
                    text = stringResource(R.string.density_apps_permission_message),
                    modifier = Modifier.padding(end = 24.dp, bottom = 8.dp),
                )
            }
            items.forEach { appModel ->
                val width = densityApps[appModel.appPackage]
                SelectorButton(
                    label = appModel.displayName,
                    value =
                        if (width == null) {
                            stringResource(R.string.density_default)
                        } else {
                            stringResource(R.string.density_width_dp, width)
                        },
                    enabled = hasPermission,
                ) {
                    val navController = findNavController()
                    if (navController.currentDestination?.id == R.id.densityAppsFragment) {
                        navController.navigate(
                            R.id.action_densityAppsFragment_to_densityAppFragment,
                            bundleOf(
                                DensityAppFragment.APP_PACKAGE to appModel.appPackage,
                                DensityAppFragment.APP_NAME to appModel.displayName,
                            ),
                        )
                    }
                }
            }
        }
    }
}
