package io.evcc.wear

import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/**
 * Receives the server list from the phone app (`modules/watch-sync`, Android
 * counterpart of the iOS watch app's WatchSyncModule.swift / ServerList.swift,
 * evcc-io/app#349) via the Wearable Data Layer and stores it in [ServerStore].
 *
 * The phone writes a persisted DataItem (not a one-off message), so the latest
 * server list is delivered even if the watch wasn't reachable when it changed -
 * same guarantee as WatchConnectivity's `updateApplicationContext`.
 */
class WatchSyncListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.use { events ->
            for (event in events) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                if (event.dataItem.uri.path != SERVERS_PATH) continue
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                val serversJson = map.getString("servers") ?: continue
                val activeServerId = map.getString("activeServerId")?.takeIf { it.isNotEmpty() }
                runBlocking { ServerStore.applySync(applicationContext, serversJson, activeServerId) }
            }
        }
        TileService.getUpdater(applicationContext).requestUpdate(LoadpointTileService::class.java)
    }

    companion object {
        const val SERVERS_PATH = "/evcc/servers"
    }
}
