package com.vandam.luma.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.vandam.luma.LumaApplication
import com.vandam.luma.R
import com.vandam.luma.data.Prefs
import com.vandam.luma.data.ServerConfig
import com.vandam.luma.helper.performAppTapHapticFeedback
import com.vandam.luma.style.SettingsTheme
import com.vandam.luma.ui.compose.MessageText
import com.vandam.luma.ui.compose.SettingsFormSpacing
import com.vandam.luma.ui.compose.SettingsInsetContentPadding
import com.vandam.luma.ui.compose.SettingsScreen
import com.vandam.luma.ui.compose.SettingsSupportingTextSpacing

class ServerFragment : Fragment() {
    private val prefs: Prefs by lazy { Prefs.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = composeView(onSwipeBack = ::goBack) { Screen() }

    @Composable
    private fun Screen() {
        val initialUrl = prefs.customServerUrl
        val textState =
            remember(initialUrl) {
                mutableStateOf(
                    TextFieldValue(
                        text = initialUrl,
                        selection = TextRange(initialUrl.length),
                    ),
                )
            }
        val errorState = remember { mutableStateOf<String?>(null) }
        val focusRequester = remember { FocusRequester() }
        val underlineColor = SettingsTheme.typography.item.color
        val context = LocalContext.current

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        fun saveServer() {
            val input = textState.value.text.trim()
            val normalizedUrl =
                if (input.isEmpty()) {
                    ""
                } else {
                    ServerConfig.normalize(input) ?: run {
                        errorState.value = getString(R.string.server_error_invalid_url)
                        return
                    }
                }

            if (normalizedUrl != prefs.customServerUrl) {
                prefs.customServerUrl = normalizedUrl
                (requireContext().applicationContext as? LumaApplication)?.closeConvexClient()
            }
            goBack()
        }

        SettingsScreen(
            title = stringResource(R.string.server_title),
            onBack = ::goBack,
            onAction = ::saveServer,
            contentPadding = SettingsInsetContentPadding,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top,
        ) {
            MessageText(text = stringResource(R.string.server_message))
            Spacer(modifier = Modifier.height(SettingsFormSpacing))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            val strokeWidth = 1.dp.toPx()
                            val y = size.height
                            drawLine(
                                color = underlineColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = strokeWidth,
                            )
                        },
            ) {
                BasicTextField(
                    value = textState.value,
                    onValueChange = { newValue ->
                        textState.value = newValue
                        if (errorState.value != null) {
                            errorState.value = null
                        }
                    },
                    modifier =
                        Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .padding(start = 6.dp, end = 4.dp, bottom = 6.dp),
                    textStyle =
                        TextStyle(
                            fontSize = 18.sp,
                            color = SettingsTheme.typography.item.color,
                        ),
                    singleLine = true,
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                saveServer()
                            },
                        ),
                )
                if (textState.value.text.isNotEmpty()) {
                    Icon(
                        painter = painterResource(id = R.drawable.close_24px),
                        contentDescription = stringResource(R.string.content_desc_clear),
                        tint = SettingsTheme.typography.item.color,
                        modifier =
                            Modifier
                                .padding(bottom = 6.dp, end = 6.dp)
                                .size(20.dp)
                                .noRippleClickable {
                                    performAppTapHapticFeedback(context)
                                    textState.value = TextFieldValue("")
                                    errorState.value = null
                                },
                    )
                }
            }

            errorState.value?.let { error ->
                Spacer(modifier = Modifier.height(SettingsSupportingTextSpacing))
                MessageText(text = error)
            }
        }
    }
}
