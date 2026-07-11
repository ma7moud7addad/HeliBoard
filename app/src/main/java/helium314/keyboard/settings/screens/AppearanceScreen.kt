// SPDX-License-Identifier: GPL-3.0-only
package com.macboard.keyboard.settings.screens

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.macboard.keyboard.keyboard.KeyboardSwitcher
import com.macboard.keyboard.keyboard.KeyboardTheme
import com.macboard.keyboard.keyboard.internal.KeyboardIconsSet
import com.macboard.keyboard.latin.R
import com.macboard.keyboard.latin.settings.Defaults
import com.macboard.keyboard.latin.utils.Log
import com.macboard.keyboard.latin.utils.getActivity
import com.macboard.keyboard.latin.utils.getStringResourceOrName
import com.macboard.keyboard.latin.utils.prefs
import com.macboard.keyboard.settings.preferences.ListPreference
import com.macboard.keyboard.settings.SettingsWithoutKey
import com.macboard.keyboard.settings.Setting
import com.macboard.keyboard.settings.preferences.Preference
import com.macboard.keyboard.settings.SearchSettingsScreen
import com.macboard.keyboard.settings.SettingsActivity
import com.macboard.keyboard.settings.preferences.SliderPreference
import com.macboard.keyboard.settings.preferences.SwitchPreference
import com.macboard.keyboard.latin.utils.Theme
import com.macboard.keyboard.settings.dialogs.ColorThemePickerDialog
import com.macboard.keyboard.settings.dialogs.CustomizeIconsDialog
import com.macboard.keyboard.settings.initPreview
import com.macboard.keyboard.settings.preferences.BackgroundImagePref
import com.macboard.keyboard.settings.preferences.CustomFontPreference
import com.macboard.keyboard.settings.preferences.KeyboardScalePreference
import com.macboard.keyboard.settings.preferences.TextInputPreference
import com.macboard.keyboard.latin.utils.previewDark
import androidx.core.content.edit
import com.macboard.keyboard.latin.settings.Settings
import com.macboard.keyboard.latin.utils.FoldableUtils
import com.macboard.keyboard.settings.dialogs.ThreeButtonAlertDialog

@Composable
fun AppearanceScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val dayNightMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)
    val items = listOf(
        R.string.settings_screen_theme,
        Settings.PREF_THEME_STYLE,
        Settings.PREF_ICON_STYLE,
        Settings.PREF_CUSTOM_ICON_NAMES,
        Settings.PREF_THEME_COLORS,
        Settings.PREF_THEME_KEY_BORDERS,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            Settings.PREF_THEME_DAY_NIGHT else null,
        if (dayNightMode) Settings.PREF_THEME_COLORS_NIGHT else null,
        Settings.PREF_NAVBAR_COLOR,
        SettingsWithoutKey.BACKGROUND_IMAGE,
        SettingsWithoutKey.BACKGROUND_IMAGE_LANDSCAPE,
        R.string.settings_category_miscellaneous,
        Settings.PREF_ENABLE_SPLIT_KEYBOARD,
        if (prefs.getBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE, Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
            || prefs.getBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD, Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
            || prefs.getBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED, Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
            || prefs.getBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE, Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
            )
            Settings.PREF_SPLIT_SPACER_SCALE_PREFIX else null,
        if (prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS))
            Settings.PREF_KEY_GAP_SCALE_PREFIX else null,
        Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX,
        Settings.PREF_BOTTOM_ROW_SCALE_PREFIX,
        Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX,
        Settings.PREF_SIDE_PADDING_SCALE_PREFIX,
        Settings.PREF_SPACE_BAR_TEXT,
        SettingsWithoutKey.CUSTOM_FONT,
        Settings.PREF_FONT_SCALE,
        SettingsWithoutKey.CUSTOM_EMOJI_FONT,
        Settings.PREF_EMOJI_FONT_SCALE,
        if (prefs.getFloat(Settings.PREF_EMOJI_FONT_SCALE, Defaults.PREF_EMOJI_FONT_SCALE) != 1f)
            Settings.PREF_EMOJI_KEY_FIT else null,
        if (prefs.getInt(Settings.PREF_EMOJI_MAX_SDK, 0) >= 24)
            Settings.PREF_EMOJI_SKIN_TONE else null,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_appearance),
        settings = items
    )
}

fun createAppearanceSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_THEME_STYLE, R.string.theme_style) { setting ->
        val ctx = LocalContext.current
        val prefs = ctx.prefs()
        val items = KeyboardTheme.STYLES.map {
            it.getStringResourceOrName("style_name_", ctx) to it
        }
        ListPreference(
            setting,
            items,
            Defaults.PREF_ICON_STYLE
        ) {
            if (it != KeyboardTheme.STYLE_HOLO) {
                if (prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS) == KeyboardTheme.THEME_HOLO_WHITE)
                    prefs.edit { remove(Settings.PREF_THEME_COLORS) }
                if (prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT) == KeyboardTheme.THEME_HOLO_WHITE)
                    prefs.edit { remove(Settings.PREF_THEME_COLORS_NIGHT) }
            }
            KeyboardIconsSet.needsReload = true // only relevant for Settings.PREF_CUSTOM_ICON_NAMES
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_ICON_STYLE, R.string.icon_style) { setting ->
        val ctx = LocalContext.current
        val items = KeyboardTheme.STYLES.map { it.getStringResourceOrName("style_name_", ctx) to it }
        ListPreference(
            setting,
            items,
            Defaults.PREF_ICON_STYLE
        ) {
            KeyboardIconsSet.needsReload = true // only relevant for Settings.PREF_CUSTOM_ICON_NAMES
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_CUSTOM_ICON_NAMES, R.string.customize_icons) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            onClick = { showDialog = true }
        )
        if (showDialog) {
            KeyboardIconsSet.instance.loadIcons(LocalContext.current)
            CustomizeIconsDialog(setting.key) { showDialog = false }
        }
    },
    Setting(context, Settings.PREF_THEME_COLORS, R.string.theme_colors) { setting ->
        val ctx = LocalContext.current
        val prefs = ctx.prefs()
        val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
        if ((b?.value ?: 0) < 0)
            Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            description = prefs.getString(setting.key, Defaults.PREF_THEME_COLORS)!!.getStringResourceOrName("theme_name_", ctx),
            onClick = { showDialog = true }
        )
        if (showDialog)
            ColorThemePickerDialog(
                onDismissRequest = { showDialog = false },
                setting = setting,
                isNight = false,
                default = Defaults.PREF_THEME_COLORS
            )
    },
    Setting(context, Settings.PREF_THEME_COLORS_NIGHT, R.string.theme_colors_night) { setting ->
        val ctx = LocalContext.current
        val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
        val prefs = ctx.prefs()
        if ((b?.value ?: 0) < 0)
            Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            description = prefs.getString(setting.key, Defaults.PREF_THEME_COLORS_NIGHT)!!.getStringResourceOrName("theme_name_", ctx),
            onClick = { showDialog = true }
        )
        if (showDialog)
            ColorThemePickerDialog(
                onDismissRequest = { showDialog = false },
                setting = setting,
                isNight = true,
                default = Defaults.PREF_THEME_COLORS_NIGHT
            )
    },
    Setting(context, Settings.PREF_THEME_KEY_BORDERS, R.string.key_borders) {
        SwitchPreference(it, Defaults.PREF_THEME_KEY_BORDERS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_THEME_DAY_NIGHT, R.string.day_night_mode, R.string.day_night_mode_summary) {
        SwitchPreference(it, Defaults.PREF_THEME_DAY_NIGHT) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_NAVBAR_COLOR, R.string.theme_navbar, R.string.day_night_mode_summary) {
        SwitchPreference(it, Defaults.PREF_NAVBAR_COLOR)
    },
    Setting(context, SettingsWithoutKey.BACKGROUND_IMAGE, R.string.customize_background_image) {
        BackgroundImagePref(it, false)
    },
    Setting(context, SettingsWithoutKey.BACKGROUND_IMAGE_LANDSCAPE,
        R.string.customize_background_image_landscape, R.string.summary_customize_background_image_landscape)
    {
        BackgroundImagePref(it, true)
    },
    Setting(context, Settings.PREF_ENABLE_SPLIT_KEYBOARD, R.string.enable_split_keyboard) {
        var show by remember { mutableStateOf(false) }
        val prefAndName = listOfNotNull(
            Settings.PREF_ENABLE_SPLIT_KEYBOARD to stringResource(R.string.button_default),
            Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE to stringResource(R.string.landscape),
            if (!FoldableUtils.isFoldable) null else
                Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED to stringResource(R.string.folded),
            if (!FoldableUtils.isFoldable) null else
                Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE to stringResource(R.string.folded) + " / " + stringResource(R.string.landscape)
        )
        Preference(
            name = stringResource(R.string.enable_split_keyboard),
            onClick = { show = true },
            description = prefAndName.filter { LocalContext.current.prefs().getBoolean(it.first, Defaults.PREF_ENABLE_SPLIT_KEYBOARD) }
                .joinToString(", ") { it.second }.takeIf { it.isNotEmpty() }
        )
        if (show) {
            ThreeButtonAlertDialog(
                onDismissRequest = { show = false },
                onConfirmed = {},
                confirmButtonText = null,
                cancelButtonText = stringResource(R.string.dialog_close),
                content = {
                    Column {
                        prefAndName.forEach {
                            SwitchPreference(name = it.second, key = it.first, default = Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
                        }
                    }
                }
            )
        }
    },
    Setting(context, Settings.PREF_SPLIT_SPACER_SCALE_PREFIX, R.string.split_spacer_scale) { setting ->
        KeyboardScalePreference(
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_SPLIT_SPACER_SCALE,
            range = 0.5f..2f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_KEY_GAP_SCALE_PREFIX, R.string.prefs_key_gap_scale) { setting ->
        KeyboardScalePreference(
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_KEY_GAP_SCALE,
            range = 0.5f..2.5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, R.string.prefs_keyboard_height_scale) { setting ->
        KeyboardScalePreference(
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_KEYBOARD_HEIGHT_SCALE,
            range = 0.3f..1.5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_BOTTOM_ROW_SCALE_PREFIX, R.string.prefs_bottom_row_scale) { setting ->
        KeyboardScalePreference(
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_BOTTOM_ROW_SCALE,
            range = 0.5f..2f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, R.string.prefs_bottom_padding_scale) { setting ->
        KeyboardScalePreference(
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_BOTTOM_PADDING_SCALE,
            range = 0f..5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SIDE_PADDING_SCALE_PREFIX, R.string.prefs_side_padding_scale) { setting ->
        KeyboardScalePreference(
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.split), stringResource(R.string.folded)),
            defaults = Defaults.PREF_SIDE_PADDING_SCALE,
            range = 0f..3f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SPACE_BAR_TEXT, R.string.prefs_space_bar_text) {
        TextInputPreference(it, Defaults.PREF_SPACE_BAR_TEXT)
    },
    Setting(context, SettingsWithoutKey.CUSTOM_FONT, R.string.custom_font) {
        CustomFontPreference(it, Settings.getCustomFontFile(LocalContext.current), R.string.custom_font)
    },
    Setting(context, Settings.PREF_FONT_SCALE, R.string.prefs_font_scale) { def ->
        SliderPreference(
            name = def.title,
            key = def.key,
            default = Defaults.PREF_FONT_SCALE,
            range = 0.5f..1.5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, SettingsWithoutKey.CUSTOM_EMOJI_FONT, R.string.custom_emoji_font) {
        CustomFontPreference(it, Settings.getCustomEmojiFontFile(LocalContext.current), R.string.custom_emoji_font)
    },
    Setting(context, Settings.PREF_EMOJI_FONT_SCALE, R.string.prefs_emoji_font_scale) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_EMOJI_FONT_SCALE,
            range = 0.5f..1.5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_EMOJI_KEY_FIT, R.string.prefs_emoji_key_fit) {
        SwitchPreference(it, Defaults.PREF_EMOJI_KEY_FIT) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_EMOJI_SKIN_TONE, R.string.prefs_emoji_skin_tone) { setting ->
        val items = listOf(
            stringResource(R.string.prefs_emoji_skin_tone_neutral) to "",
            "\uD83C\uDFFB" to "\uD83C\uDFFB",
            "\uD83C\uDFFC" to "\uD83C\uDFFC",
            "\uD83C\uDFFD" to "\uD83C\uDFFD",
            "\uD83C\uDFFE" to "\uD83C\uDFFE",
            "\uD83C\uDFFF" to "\uD83C\uDFFF"
        )
        ListPreference(setting, items, Defaults.PREF_EMOJI_SKIN_TONE) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
)

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            AppearanceScreen { }
        }
    }
}
