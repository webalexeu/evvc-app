package io.evcc.android.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.layout.ContentScale
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.LocalSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.color.isNightMode
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import io.evcc.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun deepLinkAction(uri: String): Action = actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))

/**
 * Loadpoint home-screen widget, Android counterpart of LoadpointWidget.swift /
 * LoadpointViews.swift's LoadpointCard. Per-instance server + loadpoint come
 * from LoadpointWidgetConfigActivity.
 *
 * Two sizes like iOS's systemSmall/systemMedium: the 2x2 square shows the
 * current mode as text next to the power, the wide variant adds a vertical
 * mode selector. Tapping the card opens the app on the configured loadpoint;
 * the reload button and mode buttons have their own clickable regions.
 */
// wide (mode selector) from this width on, else the square layout
private val WIDE_MIN_WIDTH = 250.dp
private val WIDE_CARD_HEIGHT = 160.dp // ≈ iOS systemMedium

// Smart-mode servers only (off/smart/now); legacy pv/minpv are not offered.
// Mirrors Loadpoint.swift's `item()`: the same raw mode carries a device-class
// label (off->Normal, now->Boost/On) for continuous heat pumps / switchable devices.
val MODES = listOf("off", "smart", "now")

fun modeLabel(context: Context, lp: Loadpoint, mode: String): String {
    if (mode == "off" && lp.chargerFeatureContinuous) return context.getString(R.string.widget_mode_normal)
    if (mode == "now") {
        if (lp.chargerFeatureContinuous) return context.getString(R.string.widget_mode_boost)
        if (lp.chargerFeatureSwitchDevice) return context.getString(R.string.widget_mode_on)
    }
    return when (mode) {
        "off" -> context.getString(R.string.widget_mode_off)
        "smart" -> context.getString(R.string.widget_mode_smart)
        "now" -> context.getString(R.string.widget_mode_now)
        // legacy servers: display only, not selectable
        "pv" -> context.getString(R.string.widget_mode_pv)
        "minpv" -> context.getString(R.string.widget_mode_minpv)
        else -> mode
    }
}

fun alwaysChargeActive(lp: Loadpoint): Boolean = lp.alwaysCharge == "on" || lp.alwaysCharge == "once"

// label plus a read-only "∞" marker on the Smart chip when Always charge is
// on/once (mirrors the SF Symbol "infinity" shown next to Smart in
// LoadpointViews.swift; no toggle in the widget for now, matches iOS)
fun modeChipLabel(context: Context, lp: Loadpoint, mode: String): String {
    val label = modeLabel(context, lp, mode)
    return if (mode == "smart" && alwaysChargeActive(lp)) "$label ∞" else label
}

enum class LpStatus(val active: Boolean) {
    DISCONNECTED(false), CONNECTED(false), WAIT_FOR_VEHICLE(false), FINISHED(false), CHARGING(true), HEATING(true),
}

data class Metric(val value: String, val unit: String, val fill: Double?)

private sealed interface LoadpointState {
    data class Data(val lp: Loadpoint, val serverId: String, val lpIndex: Int) : LoadpointState
    object NoData : LoadpointState
    object Unreachable : LoadpointState
    object NotConfigured : LoadpointState
}

// mirrors LoadpointVM.build's status derivation in Loadpoint.swift
fun status(lp: Loadpoint): LpStatus {
    val heating = lp.chargerFeatureHeating
    val soc = lp.vehicleSoc ?: 0.0
    val limit = lp.effectiveLimitSoc ?: 0.0
    return when {
        !lp.connected -> LpStatus.DISCONNECTED
        lp.charging -> if (heating) LpStatus.HEATING else LpStatus.CHARGING
        lp.enabled -> if (limit > 0 && soc >= limit) LpStatus.FINISHED else LpStatus.WAIT_FOR_VEHICLE
        else -> LpStatus.CONNECTED
    }
}

// mirrors LoadpointStatus.labelKey(heating:) resolved against evcc's own
// main.vehicleStatus.* / main.heatingStatus.* translations
fun statusLabel(context: Context, s: LpStatus, heating: Boolean): String = context.getString(
    when (s) {
        LpStatus.DISCONNECTED -> R.string.widget_lpstatus_disconnected
        LpStatus.CONNECTED -> if (heating) R.string.widget_lpheat_connected else R.string.widget_lpstatus_connected
        LpStatus.WAIT_FOR_VEHICLE ->
            if (heating) R.string.widget_lpheat_waitForVehicle else R.string.widget_lpstatus_waitForVehicle
        LpStatus.FINISHED -> R.string.widget_lpstatus_finished
        LpStatus.CHARGING -> R.string.widget_lpstatus_charging
        LpStatus.HEATING -> R.string.widget_lpheat_charging
    },
)

// mirrors LoadpointVM.build's metricValue/metricUnit/fill derivation
fun metric(lp: Loadpoint): Metric {
    val heating = lp.chargerFeatureHeating
    val soc = lp.vehicleSoc ?: 0.0
    return when {
        heating -> {
            val minT = lp.ui?.minTemp ?: 0.0
            val maxT = lp.ui?.maxTemp ?: 100.0
            val fill = if (maxT > minT) ((soc - minT) / (maxT - minT)).coerceIn(0.0, 1.0) else null
            Metric(Format.fmtNumber(soc, 1), "°C", fill)
        }
        soc > 0 -> Metric(Format.fmtNumber(soc, 0), "%", (soc / 100).coerceIn(0.0, 1.0))
        else -> {
            val kWh = ((lp.chargedEnergy ?: lp.sessionEnergy ?: 0.0)) / 1000
            Metric(Format.fmtNumber(kWh, 1), "kWh", null)
        }
    }
}

fun title(context: Context, lp: Loadpoint): String {
    val vt = lp.vehicleTitle?.trim().orEmpty()
    return vt.ifEmpty { lp.title ?: context.getString(R.string.widget_loadpoint_name) }
}

// Per-instance Glance state (DataStore, cleared by Glance when the widget is removed).
val SERVER_KEY = stringPreferencesKey("server") // absent = default server
val LP_KEY = intPreferencesKey("lp") // absent = not configured yet
private val REFRESH_KEY = longPreferencesKey("refresh")

class LoadpointWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // A running Glance session serves update() by recomposing with the new
        // state, not by calling provideGlance again. So: load once up front for
        // the first frame, then reload inside the composition whenever the
        // state (config or refresh nonce) changes.
        val initialPrefs = getAppWidgetState<Preferences>(context, id)
        val initial = load(context, initialPrefs)
        provideContent {
            val prefs = currentState<Preferences>()
            var state by remember { mutableStateOf(initial) }
            LaunchedEffect(prefs) {
                if (prefs != initialPrefs) state = load(context, prefs)
            }
            Content(context, state, prefs[LP_KEY]?.let { prefs[SERVER_KEY] to it })
        }
    }

    private suspend fun load(context: Context, prefs: Preferences): LoadpointState = withContext(Dispatchers.IO) {
        val lpIndex = prefs[LP_KEY] ?: return@withContext LoadpointState.NotConfigured
        val server = SharedStore.server(context, prefs[SERVER_KEY]) ?: return@withContext LoadpointState.NotConfigured
        when (val out = ApiClient.fetch(server, ".loadpoints[$lpIndex]")) {
            is FetchOutcome.Success ->
                Loadpoint.parse(out.json)?.let { LoadpointState.Data(it, server.id, lpIndex) }
                    ?: LoadpointState.NoData
            FetchOutcome.NoData -> LoadpointState.NoData
            FetchOutcome.Failure -> LoadpointState.Unreachable
        }
    }

    companion object {
        /** Re-fetch every placed widget (mirrors iOS's reloadAllTimelines). */
        suspend fun refreshAll(context: Context) {
            val widget = LoadpointWidget()
            for (id in GlanceAppWidgetManager(context).getGlanceIds(LoadpointWidget::class.java)) {
                updateAppWidgetState(context, id) { it[REFRESH_KEY] = System.currentTimeMillis() }
                widget.update(context, id)
            }
        }
    }

    @Composable
    private fun Content(context: Context, state: LoadpointState, resolved: Pair<String?, Int>?) {
        val notConfigured = state == LoadpointState.NotConfigured
        // mirrors LoadpointView.deepLink in LoadpointViews.swift: always the
        // configured loadpoint (even in noData/unreachable, so the user can go
        // fix things in-app). lp is 1-based like the web UI. A null serverId means the default server, so the
        // query param is omitted. Unconfigured just opens the app.
        val deepLink = resolved?.let { (serverId, lpIndex) ->
            "evcc://loadpoint?lp=${lpIndex + 1}" + (serverId?.let { "&server=$it" } ?: "")
        } ?: "evcc://loadpoint"
        // Launcher cells are taller than wide; iOS cards are square (small) or
        // 2:1 (medium). Cap the card height accordingly and center it in the cell.
        val size = LocalSize.current
        val wide = size.width >= WIDE_MIN_WIDTH
        val cardHeight = minOf(size.height, if (wide) WIDE_CARD_HEIGHT else size.width)
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = GlanceModifier.fillMaxWidth().height(cardHeight)
                    .background(if (notConfigured) notConfiguredBackground else cardBackground)
                    .cornerRadius(20.dp)
                    .clickable(deepLinkAction(deepLink))
                    .padding(16.dp),
                verticalAlignment = Alignment.Vertical.Top,
            ) {
                when (state) {
                    is LoadpointState.Data -> LoadpointBody(context, state, wide)
                    LoadpointState.NoData -> MessageBody(
                        context.getString(R.string.widget_noData_title),
                        context.getString(R.string.widget_noData_body),
                    )
                    LoadpointState.Unreachable -> MessageBody(
                        context.getString(R.string.widget_unreachable_title),
                        context.getString(R.string.widget_unreachable_body),
                    )
                    LoadpointState.NotConfigured -> NotConfiguredBody(context)
                }
            }
        }
    }

    @Composable
    private fun LoadpointBody(context: Context, state: LoadpointState.Data, wide: Boolean) {
        if (wide) {
            Row(modifier = GlanceModifier.fillMaxSize()) {
                Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                    LoadpointInfo(context, state, showMode = false)
                }
                Spacer(GlanceModifier.width(14.dp))
                Column(modifier = GlanceModifier.width(116.dp).fillMaxHeight()) {
                    ModeSelectorColumn(context, state)
                }
            }
        } else {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                LoadpointInfo(context, state, showMode = true)
            }
        }
    }

    // title / status / metric / power column (mirrors LoadpointCard's `left`);
    // the square size shows the current mode as text next to the power.
    @Composable
    private fun ColumnScope.LoadpointInfo(context: Context, state: LoadpointState.Data, showMode: Boolean) {
        val lp = state.lp
        val s = status(lp)
        val m = metric(lp)
        val heating = lp.chargerFeatureHeating

        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(title(context, lp), style = titleStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(8.dp))
            Image(
                provider = ImageProvider(R.drawable.ic_reload),
                contentDescription = null,
                colorFilter = ColorFilter.tint(textSecondary),
                modifier = GlanceModifier.width(15.dp).height(15.dp).clickable(actionRunCallback<ReloadAction>()),
            )
        }

        Row(modifier = GlanceModifier.padding(top = 3.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Box(modifier = GlanceModifier.width(7.dp).height(7.dp).background(statusColor(s.active, heating)).cornerRadius(4.dp)) {}
            Spacer(GlanceModifier.width(5.dp))
            Text(statusLabel(context, s, heating), style = statusStyle.copy(color = statusColor(s.active, heating)), maxLines = 1)
        }

        Spacer(GlanceModifier.defaultWeight())

        // Glance has no baseline alignment: bottom-align and lift the smaller
        // text by the descent difference (≈0.24em for Roboto) to fake one.
        Row(verticalAlignment = Alignment.Vertical.Bottom) {
            Text(m.value, style = metricStyle, maxLines = 1)
            Text(" ${m.unit}", style = metricUnitStyle, maxLines = 1, modifier = GlanceModifier.padding(bottom = 4.dp))
        }

        if (m.fill != null) {
            Spacer(GlanceModifier.height(6.dp))
            val dark = context.isNightMode
            Image(
                provider = ImageProvider(
                    ProgressBarRenderer.render(
                        fraction = m.fill,
                        fillColor = barFillColor(lp.connected, heating),
                        trackColor = barTrackColor(dark),
                        striped = s.active,
                        stripeColor = barStripeColor(heating),
                    ),
                ),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
            )
        }

        Spacer(GlanceModifier.defaultWeight())

        val power = lp.chargePower?.let { Format.fmtW(it) } ?: "–"
        val (powerValue, powerUnit) = splitValueUnit(power)
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.Bottom) {
            Text(powerValue, style = powerStyle, maxLines = 1)
            Text(" $powerUnit", style = powerUnitStyle, maxLines = 1, modifier = GlanceModifier.padding(bottom = 1.dp))
            if (showMode && lp.mode != null) {
                Spacer(GlanceModifier.defaultWeight())
                Text(modeChipLabel(context, lp, lp.mode), style = modeLabelStyle, maxLines = 1, modifier = GlanceModifier.padding(bottom = 1.dp))
            }
        }
    }

    // wide size: a vertical column of full-width buttons (mirrors modeSelector in LoadpointViews.swift)
    @Composable
    private fun ColumnScope.ModeSelectorColumn(context: Context, state: LoadpointState.Data) {
        MODES.forEachIndexed { i, mode ->
            if (i > 0) Spacer(GlanceModifier.height(6.dp))
            ModeChip(
                context, lp = state.lp, mode = mode, serverId = state.serverId, lpIndex = state.lpIndex,
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            )
        }
    }

    @Composable
    private fun ModeChip(
        context: Context,
        lp: Loadpoint,
        mode: String,
        serverId: String,
        lpIndex: Int,
        modifier: GlanceModifier = GlanceModifier,
    ) {
        val selected = mode == lp.mode
        Box(
            modifier = modifier
                .background(if (selected) modeSelectedBackground else modeUnselectedBackground)
                .cornerRadius(9.dp)
                .padding(horizontal = 8.dp, vertical = 5.dp)
                .clickable(
                    actionRunCallback<ModeAction>(
                        actionParametersOf(
                            ModeAction.serverKey to serverId,
                            ModeAction.lpKey to (lpIndex + 1), // API is 1-based
                            ModeAction.modeKey to mode,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = modeChipLabel(context, lp, mode),
                style = modeChipStyle.copy(color = if (selected) modeSelectedText else modeUnselectedText),
            )
        }
    }

    @Composable
    private fun MessageBody(title: String, message: String) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Text(title, style = messageTitleStyle)
            Text(message, style = messageBodyStyle)
        }
    }

    @Composable
    private fun NotConfiguredBody(context: Context) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Text(context.getString(R.string.widget_setup_title), style = notConfiguredTitleStyle)
            Text(context.getString(R.string.widget_setup_body), style = notConfiguredBodyStyle)
        }
    }
}

/** Applies a charge mode from a widget button, then refreshes the widget. */
class ModeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val serverId = parameters[serverKey]
        val lp = parameters[lpKey] ?: return
        val mode = parameters[modeKey] ?: return
        val server = SharedStore.server(context, serverId) ?: return
        withContext(Dispatchers.IO) {
            ApiClient.post(server, "/api/loadpoints/$lp/mode/$mode")
        }
        LoadpointWidget.refreshAll(context)
    }

    companion object {
        val serverKey = ActionParameters.Key<String>("serverId")
        val lpKey = ActionParameters.Key<Int>("lp")
        val modeKey = ActionParameters.Key<String>("mode")
    }
}

/** Forces a fresh fetch, mirrors iOS's ReloadIntent. */
class ReloadAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        LoadpointWidget.refreshAll(context)
    }
}

class EvccLoadpointWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LoadpointWidget()
}
