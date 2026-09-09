package io.evcc.wear

import android.content.Context
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement

/**
 * protolayout builder for the loadpoint tile. Blueprint is LoadpointWidget.kt's
 * LoadpointCard (#255), minus the width classes — the watch has one width.
 *
 * Target layout (single column, centered):
 *   - title           title(lp)  -> vehicleTitle ?: lp.title
 *   - big value+unit  metric(lp) -> "34.9" "kWh" / "72" "%" / temp
 *   - status line     statusLabel(status(lp))         + statusColor(...)
 *   - mode chip row   modes(lp).map { chip(modeLabel(it), selected = it == lp.mode) }
 *                     each chip tappable -> click id "mode:<raw>"
 *   - reload icon     tappable -> click id "reload"
 *   - progress arc    metric(lp).fill, along the tile edge (protolayout Arc)
 *
 * States: NotConfigured -> "Open the app to add a server" ; Unreachable / NoData
 * -> title + reload only (LoadpointWidget.kt does the same).
 *
 * STATUS: skeleton — returns a placeholder. See targets/wear/PLAN.md task 5.
 * Strings resolve against :wear R.string.widget_* (generated from
 * targets/widget/Localizable.xcstrings at prebuild, same as #255).
 */
object LoadpointTileLayout {

    fun build(context: Context, state: TileState, dark: Boolean): LayoutElement {
        return when (state) {
            is TileState.Data -> dataLayout(context, state.lp, dark)
            TileState.NoData -> messageLayout(context, /* R.string.widget_state_nodata */ "No data", dark)
            TileState.Unreachable -> messageLayout(context, /* R.string.widget_state_unreachable */ "Unreachable", dark)
            TileState.NotConfigured -> messageLayout(context, /* R.string.widget_state_setup */ "Open the app to add a server", dark)
        }
    }

    private fun dataLayout(context: Context, lp: Loadpoint, dark: Boolean): LayoutElement {
        val m = metric(lp)
        val (value, unit) = m.value to m.unit
        val st = status(lp)
        val modeList = modes(lp)
        val current = lp.mode

        // TODO build with androidx.wear.protolayout.material3 (primaryLayout /
        // titleCard / buttonGroup) or raw LayoutElementBuilders:
        //   Column(
        //     Text(title(lp), Theme.textPrimary(dark))
        //     Row( Text(value, big) ; Text(unit, small, Theme.textSecondary(dark)) )
        //     Text(statusLabel(st), Theme.statusColor(st.active, heating, dark))
        //     Row(modeList.map { modeChip(context, it, it == current, dark) })
        //     iconButton("ic_reload", clickable = clickable("reload"))
        //   )
        //   + edge Arc for m.fill with Theme.barFill(lp.connected, heating, dark)
        return placeholder(context, "${title(context, lp)}  $value $unit")
    }

    private fun modeChip(context: Context, rawMode: String, selected: Boolean, dark: Boolean): LayoutElement {
        // label: modeLabel(context, lp, rawMode) once R.string wiring lands;
        // bg/text: Theme.modeSelected*/modeUnselected*(dark); clickable id "mode:$rawMode"
        return placeholder(context, rawMode)
    }

    private fun messageLayout(context: Context, text: String, dark: Boolean): LayoutElement =
        placeholder(context, text)

    /** Temporary stand-in so the module compiles before the real builders land. */
    private fun placeholder(context: Context, text: String): LayoutElement =
        androidx.wear.protolayout.LayoutElementBuilders.Text.Builder()
            .setText(text)
            .build()
}

/** title(lp) from LoadpointWidget.kt; string fallback resolved by caller/resources. */
fun title(context: Context, lp: Loadpoint): String {
    val vt = lp.vehicleTitle?.trim().orEmpty()
    return vt.ifEmpty { lp.title ?: "Loadpoint" }
}
