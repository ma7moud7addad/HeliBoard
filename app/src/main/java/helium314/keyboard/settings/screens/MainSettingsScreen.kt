// SPDX-License-Identifier: GPL-3.0-only
package com.macboard.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.macboard.keyboard.latin.R
import com.macboard.keyboard.latin.utils.JniUtils
import com.macboard.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import com.macboard.keyboard.latin.utils.SubtypeSettings
import com.macboard.keyboard.latin.utils.NextScreenIcon
import com.macboard.keyboard.settings.SearchSettingsScreen
import com.macboard.keyboard.latin.utils.Theme
import com.macboard.keyboard.settings.initPreview
import com.macboard.keyboard.settings.preferences.Preference
import com.macboard.keyboard.latin.utils.previewDark
import com.macboard.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import com.macboard.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS

@Composable
fun MainSettingsScreen(
    onClickAbout: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickPreferences: () -> Unit,
    onClickToolbar: () -> Unit,
    onClickGestureTyping: () -> Unit,
    onClickDataGathering: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickLanguage: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
        val context = LocalContext.current
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
            ) {
                Preference(
                    name = stringResource(R.string.language_and_layouts_title),
                    description = enabledSubtypes.joinToString(", ") { it.displayName() },
                    onClick = onClickLanguage,
                    icon = R.drawable.ic_settings_languages
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_preferences),
                    onClick = onClickPreferences,
                    icon = R.drawable.ic_settings_preferences
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_appearance),
                    onClick = onClickAppearance,
                    icon = R.drawable.ic_settings_appearance
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_toolbar),
                    onClick = onClickToolbar,
                    icon = R.drawable.ic_settings_toolbar
                ) { NextScreenIcon() }
                
                // --- إظهار القائمة فقط إذا كانت المكتبة مفعلة ومتاحة ---
                if (JniUtils.isGestureLibraryAvailable()) {
                    Preference(
                        name = stringResource(R.string.settings_screen_gesture),
                        onClick = onClickGestureTyping,
                        icon = R.drawable.ic_settings_gesture
                    ) { NextScreenIcon() }
                }
                
                // we don't even show the menu if data gathering phase ended more than 2 weeks ago
                if (JniUtils.sHaveGestureLib && System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS)
                    Preference(
                        name = stringResource(R.string.gesture_data_screen),
                        onClick = onClickDataGathering,
                        icon = R.drawable.ic_settings_gesture
                    ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_correction),
                    onClick = onClickTextCorrection,
                    icon = R.drawable.ic_settings_correction
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_secondary_layouts),
                    onClick = onClickLayouts,
                    icon = R.drawable.ic_ime_switcher
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.dictionary_settings_category),
                    onClick = onClickDictionaries,
                    icon = R.drawable.ic_dictionary
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_advanced),
                    onClick = onClickAdvanced,
                    icon = R.drawable.ic_settings_advanced
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_about),
                    onClick = onClickAbout,
                    icon = R.drawable.ic_settings_about
                ) { NextScreenIcon() }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
