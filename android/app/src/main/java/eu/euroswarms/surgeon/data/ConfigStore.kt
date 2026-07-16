package eu.euroswarms.surgeon.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "surgeon_config")

/** Persists [AppConfig] as a single JSON blob so schema changes stay backward-compatible. */
class ConfigStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("app_config_json")

    val configFlow: Flow<AppConfig> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { raw ->
            runCatching { json.decodeFromString<AppConfig>(raw) }.getOrDefault(AppConfig())
        } ?: AppConfig()
    }

    suspend fun get(): AppConfig = configFlow.first()

    suspend fun save(config: AppConfig) {
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(AppConfig.serializer(), config)
        }
    }
}
