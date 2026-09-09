package io.evcc.wear

import android.app.Activity
import android.os.Bundle

/**
 * Config entry point for the Wear tile: server URL + optional basic auth +
 * loadpoint picker, saved to [ServerStore]. Opened from the tile's
 * "NotConfigured" state and from the watch app launcher.
 *
 * Counterpart of LoadpointWidgetConfigActivity.kt (#255), but on-watch:
 * standalone has no phone-side picker.
 *
 * STATUS: skeleton. Real UI is Compose-for-Wear (ConfigScreen). See
 * targets/wear/PLAN.md task 7.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO setContent { EvccWearTheme { ConfigScreen(...) } }
        //
        // ConfigScreen flow:
        //   1. ScalingLazyColumn:
        //        - TextField(url)            "http://<host>:7070" or gateway URL
        //        - Switch(authRequired) -> TextField(username), TextField(password)
        //        - Button("Load loadpoints")  -> lifecycleScope { ApiClient.loadpointTitles(cfg) }
        //        - Picker(titles)             -> loadpointIndex
        //        - Button("Save")             -> ServerStore.save(this, cfg); finish()
        //   2. On open, prefill from ServerStore.load(this).
        //
        // Keep it forgiving: allow saving with just a URL (loadpointIndex 0),
        // the tile shows Unreachable/NoData until it resolves — same as the widget.
        finish()
    }
}
