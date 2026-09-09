package io.evcc.wear

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The single evcc server + loadpoint the tile talks to, standalone.
 *
 * Unlike the phone widget (which reads a server list the RN app writes to the
 * app sandbox — SharedStore.kt), the watch has no RN app, so the config is
 * entered once in the Wear config activity (ConfigScreen.kt) and lives here.
 */
data class ServerConfig(
    val url: String,
    val authRequired: Boolean = false,
    val username: String? = null,
    val password: String? = null,
    val loadpointIndex: Int = 0,
) {
    val configured: Boolean get() = url.isNotBlank()
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "evcc_wear")

object ServerStore {
    private val URL = stringPreferencesKey("url")
    private val AUTH = booleanPreferencesKey("authRequired")
    private val USER = stringPreferencesKey("username")
    private val PASS = stringPreferencesKey("password")
    private val LP = intPreferencesKey("loadpointIndex")

    suspend fun load(context: Context): ServerConfig? {
        val p = context.dataStore.data.first()
        val url = p[URL] ?: return null
        return ServerConfig(
            url = url,
            authRequired = p[AUTH] ?: false,
            username = p[USER],
            password = p[PASS],
            loadpointIndex = p[LP] ?: 0,
        )
    }

    fun flow(context: Context) = context.dataStore.data.map { p ->
        p[URL]?.let {
            ServerConfig(it, p[AUTH] ?: false, p[USER], p[PASS], p[LP] ?: 0)
        }
    }

    suspend fun save(context: Context, cfg: ServerConfig) {
        context.dataStore.edit { p ->
            p[URL] = cfg.url.trim()
            p[AUTH] = cfg.authRequired
            if (cfg.username.isNullOrBlank()) p.remove(USER) else p[USER] = cfg.username
            if (cfg.password.isNullOrEmpty()) p.remove(PASS) else p[PASS] = cfg.password
            p[LP] = cfg.loadpointIndex
        }
    }

    suspend fun clear(context: Context) {
        context.dataStore.edit { it.clear() }
    }
}
