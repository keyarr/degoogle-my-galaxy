package dev.degoogle.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "degoogle_prefs")

/**
 * Preferências auxiliares. Apenas dados auxiliares (última versão consultada,
 * prompts já exibidos, operação pendente). O ESTADO é sempre re-derivado do
 * sistema — nunca lido daqui.
 */
class Prefs(private val context: Context) {

    private object Keys {
        val LAST_RELEASE_CHECKED = longPreferencesKey("last_release_checked_ms")
        val LAST_RELEASE_VERSION = stringPreferencesKey("last_release_version")
        val SETUP_PROMPT_SEEN = booleanPreferencesKey("setup_prompt_seen")
        val PENDING_OPERATION = stringPreferencesKey("pending_operation")
        val PENDING_SINCE = longPreferencesKey("pending_since")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val EXPERIMENTAL_OPT_IN = booleanPreferencesKey("experimental_opt_in")
    }

    val setupPromptSeen: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.SETUP_PROMPT_SEEN] ?: false }

    val pendingOperation: Flow<String?> =
        context.dataStore.data.map { it[Keys.PENDING_OPERATION] }

    val notificationsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.NOTIFICATIONS_ENABLED] ?: true }

    val experimentalOptIn: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.EXPERIMENTAL_OPT_IN] ?: false }

    suspend fun markSetupPromptSeen() {
        context.dataStore.edit { it[Keys.SETUP_PROMPT_SEEN] = true }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setExperimentalOptIn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.EXPERIMENTAL_OPT_IN] = enabled }
    }

    suspend fun markOperationPending(op: String) {
        context.dataStore.edit {
            it[Keys.PENDING_OPERATION] = op
            it[Keys.PENDING_SINCE] = System.currentTimeMillis()
        }
    }

    suspend fun clearPendingOperation() {
        context.dataStore.edit {
            it.remove(Keys.PENDING_OPERATION)
            it.remove(Keys.PENDING_SINCE)
        }
    }

    suspend fun hasPendingOperation(): Boolean = pendingOperation.first() != null

    suspend fun recordReleaseChecked(version: String) {
        context.dataStore.edit {
            it[Keys.LAST_RELEASE_CHECKED] = System.currentTimeMillis()
            it[Keys.LAST_RELEASE_VERSION] = version
        }
    }
}
