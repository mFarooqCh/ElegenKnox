package com.elegen.elegencashbook.data.local.prefs

import androidx.appcompat.app.AppCompatDelegate

/** User's theme choice. Persisted by [key]; applied via [nightMode]. Default is [SYSTEM]. */
enum class ThemeMode(val key: String, val nightMode: Int, val label: String, val desc: String) {
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, "System", "Match your device setting"),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO, "Light", "Always use the light theme"),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES, "Dark", "Always use the dark theme");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
