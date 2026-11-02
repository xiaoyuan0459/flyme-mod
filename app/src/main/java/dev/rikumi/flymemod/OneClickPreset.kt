package dev.rikumi.flymemod

import android.content.SharedPreferences

/**
 * The home master switch disables every feature without discarding the saved configuration.
 * Disabling snapshots the current values into private keys; enabling restores them. Enabling
 * without a snapshot leaves the existing configuration untouched and never applies developer
 * defaults.
 */
internal object OneClickPreset {
    private const val BACKUP_PRESENT = "module_master_backup_present"
    private const val BACKUP_BOOLEAN_PREFIX = "module_master_backup_boolean_"
    private const val BACKUP_INT_PREFIX = "module_master_backup_int_"

    // Legacy and custom controls have no SwitchItem in the settings catalog.
    private val additionalSwitches = setOf(
        ModuleSettings.AOD_CLOCK_MONOSPACE,
        ModuleSettings.AOD_CLOCK_SPACING_ENABLED,
        ModuleSettings.BACK_GESTURE_WIDTH_ENABLED,
        ModuleSettings.NETWORK_DARK_SPINNER,
        ModuleSettings.STORE_HIDE_DOWNLOAD_RECOMMENDATIONS,
        ModuleSettings.STORE_HIDE_EVERYONE_WATCHING,
        ModuleSettings.STORE_HIDE_HIGH_RATED,
        ModuleSettings.STORE_HIDE_STAR_APPS,
        ModuleSettings.STORE_HIDE_YOU_MAY_LIKE,
        ModuleSettings.STORE_DETAIL_HIDE_REVIEWS,
        ModuleSettings.STORE_DETAIL_HIDE_SAME_MODEL,
        ModuleSettings.STORE_DETAIL_HIDE_TOPICS,
        ModuleSettings.STORE_EMPTY_APPLICATION_PAGE,
        ModuleSettings.STORE_HIDE_COMMUNITY,
        ModuleSettings.STORE_HIDE_DAILY,
        ModuleSettings.STORE_HIDE_POPULAR,
        ModuleSettings.STORE_HIDE_SEARCH_HOT,
        ModuleSettings.STORE_HIDE_SEARCH_RECOMMENDATIONS,
        ModuleSettings.WHITE_ACTIVE,
    )
    private val applicationSwitches = setOf(
        ModuleSettings.APP_IMMERSIVE_NAVIGATION, ModuleSettings.DARK_APP_SPECIAL_PACKAGES,
        ModuleSettings.HIDDEN_LAUNCHER_APPS_ENABLED,
        ModuleSettings.LANDSCAPE_GAME_ENABLED,
    )
    private val switches: Set<String> by lazy {
        buildSet {
            fun collect(items: List<SettingsItem>) {
                items.forEach { item ->
                    when (item) {
                        is SwitchItem -> {
                            add(item.key)
                            collect(item.children)
                        }
                        is FolderBlockItem -> add(item.key)
                        is ColorItem -> add(item.key)
                        else -> Unit
                    }
                }
            }
            SETTINGS_GROUPS.values.flatten().forEach { collect(it.items) }
            add(HIDE_DISABLED_APPS_ITEM.key)
            addAll(additionalSwitches)
            addAll(applicationSwitches)
        }
    }
    private val modeKeys = setOf(
        ModuleSettings.TICKER_DISPLAY_MODE,
        ModuleSettings.STATUS_BAR_CHARGING_INFO,
        ModuleSettings.CONTROL_CENTER_STYLE, ModuleSettings.VOLUME_FIRST_FOUR,
        ModuleSettings.LOCK_CLOCK_FONT, ModuleSettings.AOD_CLOCK_FONT,
        ModuleSettings.STATUS_BAR_CLOCK_FONT, ModuleSettings.CONTROL_CENTER_CLOCK_FONT,
        ModuleSettings.CLOCK_FONT_WEIGHT, ModuleSettings.STATUS_BAR_CLOCK_WEIGHT,
        ModuleSettings.CONTROL_CENTER_CLOCK_WEIGHT,
    )

    fun hasBackup(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(BACKUP_PRESENT, false)

    fun apply(preferences: SharedPreferences, editor: SharedPreferences.Editor, enabled: Boolean) {
        if (enabled) {
            // A normal re-enable restores the configuration captured on disable. Switches the
            // user turned on while the module was disabled are kept. First enable has no
            // snapshot and therefore leaves the current configuration untouched.
            if (hasBackup(preferences)) restore(preferences, editor)
        } else {
            if (!hasBackup(preferences)) backup(preferences, editor)
            disableAll(editor)
        }
    }

    private fun disableAll(editor: SharedPreferences.Editor) {
        switches.forEach { editor.putBoolean(it, false) }
        modeKeys.forEach { editor.putInt(it, 0) }
    }

    private fun backup(preferences: SharedPreferences, editor: SharedPreferences.Editor) {
        switches.forEach { key ->
            editor.putBoolean(BACKUP_BOOLEAN_PREFIX + key, preferences.getBoolean(key, false))
        }
        modeKeys.forEach { key ->
            editor.putInt(BACKUP_INT_PREFIX + key, preferences.getInt(key, 0))
        }
        editor.putBoolean(BACKUP_PRESENT, true)
    }

    private fun restore(preferences: SharedPreferences, editor: SharedPreferences.Editor) {
        switches.forEach { key ->
            val backup = BACKUP_BOOLEAN_PREFIX + key
            if (!preferences.getBoolean(key, false) && preferences.contains(backup))
                editor.putBoolean(key, preferences.getBoolean(backup, false))
            editor.remove(backup)
        }
        modeKeys.forEach { key ->
            val backup = BACKUP_INT_PREFIX + key
            if (preferences.getInt(key, 0) == 0 && preferences.contains(backup))
                editor.putInt(key, preferences.getInt(backup, 0))
            editor.remove(backup)
        }
        editor.remove(BACKUP_PRESENT)
    }
}
