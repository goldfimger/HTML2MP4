package com.htmltomp4.converter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.htmltomp4.converter.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

data class UserPreferences(
    val outputTreeUri: String? = null,
    val outputTreeLabel: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val OUTPUT_TREE_URI = stringPreferencesKey("output_tree_uri")
        val OUTPUT_TREE_LABEL = stringPreferencesKey("output_tree_label")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val userPreferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        val themeName = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name
        val themeMode = runCatching { ThemeMode.valueOf(themeName) }.getOrDefault(ThemeMode.SYSTEM)
        UserPreferences(
            outputTreeUri = prefs[Keys.OUTPUT_TREE_URI],
            outputTreeLabel = prefs[Keys.OUTPUT_TREE_LABEL],
            themeMode = themeMode
        )
    }

    suspend fun saveOutputFolder(uriString: String, label: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OUTPUT_TREE_URI] = uriString
            prefs[Keys.OUTPUT_TREE_LABEL] = label
        }
    }

    suspend fun saveThemeMode(themeMode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = themeMode.name
        }
    }
}
