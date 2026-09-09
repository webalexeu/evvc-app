package io.evcc.wear

import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future

/**
 * Wear OS tile for one evcc loadpoint. Standalone: reads [ServerStore] and calls
 * evcc directly via [ApiClient]. Counterpart of LoadpointWidget.kt (#255); the
 * data + view-model layer (ApiClient / Loadpoint / status() / metric() / modes())
 * is shared, only the rendering differs (protolayout instead of Glance).
 *
 * STATUS: skeleton. See targets/wear/PLAN.md task 6.
 */
class LoadpointTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = scope.future {
        val state = loadState()

        // TODO handle click actions carried in requestParams.currentState:
        //   - "reload"        -> just re-fetch (fall through)
        //   - "mode:<m>"      -> ApiClient.setMode(cfg, cfg.loadpointIndex, m), then re-fetch
        // Click ids are set on the tappable elements in LoadpointTileLayout.

        val dark = requestParams.deviceConfiguration
            ?.let { /* it.screenColorScheme? / DeviceParameters */ true } ?: true

        val layout = LoadpointTileLayout.build(this@LoadpointTileService, state, dark)

        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(REFRESH_INTERVAL_MS)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build(),
                    )
                    .build(),
            )
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = Futures.immediateFuture(
        ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            // TODO addIdToImageMapping("ic_reload", ...), mode icons, evcc mark
            .build(),
    )

    private suspend fun loadState(): TileState {
        val cfg = ServerStore.load(this) ?: return TileState.NotConfigured
        return when (val out = ApiClient.loadpoint(cfg, cfg.loadpointIndex)) {
            is FetchOutcome.Success ->
                Loadpoint.parse(out.json)?.let { TileState.Data(it, cfg) } ?: TileState.NoData
            FetchOutcome.NoData -> TileState.NoData
            FetchOutcome.Failure -> TileState.Unreachable
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val REFRESH_INTERVAL_MS = 30L * 60 * 1000 // 30 min, matches the widget
    }
}

/** Mirrors LoadpointWidget.kt's private LoadpointState. */
sealed interface TileState {
    data class Data(val lp: Loadpoint, val cfg: ServerConfig) : TileState
    object NoData : TileState
    object Unreachable : TileState
    object NotConfigured : TileState
}
