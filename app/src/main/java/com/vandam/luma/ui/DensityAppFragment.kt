package com.vandam.luma.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import com.vandam.luma.R
import com.vandam.luma.data.Prefs
import com.vandam.luma.helper.DensityManager
import com.vandam.luma.ui.compose.SingleChoiceOption
import com.vandam.luma.ui.compose.SingleChoiceScreen

class DensityAppFragment : Fragment() {
    private val prefs by lazy { Prefs.getInstance(requireContext()) }
    private val appPackage by lazy { arguments?.getString(APP_PACKAGE).orEmpty() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = composeView(onSwipeBack = ::goBack) { Screen() }

    @Composable
    private fun Screen() {
        val current = prefs.densityApps[appPackage]
        SingleChoiceScreen(
            title = arguments?.getString(APP_NAME).orEmpty().ifBlank { stringResource(R.string.settings_density_apps) },
            onBack = ::goBack,
            options =
                listOf(
                    SingleChoiceOption(
                        title = stringResource(R.string.density_default),
                        selected = current == null,
                        onClick = { select(null) },
                    ),
                ) +
                    DensityManager.SMALLEST_WIDTH_OPTIONS.map { width ->
                        SingleChoiceOption(
                            title = stringResource(R.string.density_width_dp, width),
                            selected = current == width,
                            onClick = { select(width) },
                        )
                    },
        )
    }

    private fun select(width: Int?) {
        if (appPackage.isNotBlank()) {
            prefs.densityApps =
                if (width == null) {
                    prefs.densityApps - appPackage
                } else {
                    prefs.densityApps + (appPackage to width)
                }
        }
        goBack()
    }

    companion object {
        const val APP_PACKAGE = "app_package"
        const val APP_NAME = "app_name"
    }
}
