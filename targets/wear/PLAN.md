# Wear OS loadpoint tile — standalone mode

Branch: `feat/wear-loadpoint-tile` (off `upstream/main`, includes #255).

Goal: the same per‑loadpoint glanceable surface as the Android home‑screen widget
(#255), on the wrist — power / SoC, status, and the Off / Solar / Min+Solar / Fast
(or Off / Smart / Fast) mode selector, with a reload button.

## Delivery: standalone Tile (+ minimal config app)

- **Tile** (`androidx.wear.tiles` + `androidx.wear.protolayout`) — the primary
  surface. Swipe‑accessible, refreshes on interval + on tap. Mode buttons and
  reload trigger an immediate refresh.
- **Compose‑for‑Wear config activity** — one screen: server URL + optional basic
  auth + loadpoint picker. Written to a `DataStore` the tile reads.
- No complication in v1 (possible follow‑up: charge power / SoC data point).

### Why not reuse the Glance widget UI

`androidx.glance:glance-wear-tiles` is abandoned by Google (last at 1.1.0‑alpha,
not maintained). Wear Tiles today = protolayout builders. So the **UI is a
rewrite**; the non‑UI half ports directly.

### Why standalone (not phone‑tethered Data Layer)

Standalone keeps the whole data path (`ApiClient`) unchanged and drops the
Wearable Data Layer entirely. The watch calls evcc directly — works on home
WiFi (LAN) or against the self‑hosted gateway for anywhere access. Trade‑off:
no connectivity when the watch has no route to the server; accepted for v1.
Phone‑tethering + a phone‑side config screen is a later option if this proves
limiting.

## What ports from `targets/android-widget/kotlin/` (package → `io.evcc.wear`)

| source | reuse | notes |
|---|---|---|
| `ApiClient.kt` | **verbatim** logic | `StoredServer` → `ServerConfig`; drop `loadpointTitles` R.string fallback |
| `Format.kt` | **verbatim** | only `java.util.Locale` |
| `Loadpoint` data class + `parse()` | **verbatim** | moved to `Loadpoint.kt` |
| `status()` / `metric()` / `modes()` / `smartModeServer()` / `alwaysChargeActive()` | **verbatim** | pure; `modeLabel()`/`statusLabel()` need Wear string resources |
| `Theme.kt` | **hex values only** | Glance `ColorProvider` → protolayout `argb` ints (`Theme.kt` here) |
| `LoadpointWidget.kt` layout | **spec only** | width‑class logic is a blueprint for the tile layout |
| `SharedStore.kt` / `LoadpointWidgetConfigActivity.kt` / `ProgressBarRenderer.kt` / `WidgetPreview.kt` | not reused | standalone config is on‑watch; progress = protolayout arc |

## Module layout (created by the config plugin at prebuild)

```
:wear                       new Gradle module, com.android.application, wear feature
  AndroidManifest.xml       TileService + config MainActivity; standalone meta-data
  src/main/java/io/evcc/wear/
    ApiClient.kt            ← targets/wear/kotlin (copied at prebuild)
    Format.kt
    Loadpoint.kt
    ServerStore.kt
    Theme.kt
    LoadpointTileService.kt
    LoadpointTileLayout.kt
    MainActivity.kt
    ConfigScreen.kt
  src/main/res/values*/strings.xml   generated from targets/widget/Localizable.xcstrings (reuse the #255 generator)
```

Phone app manifest gets `<meta-data android:name="com.google.android.wearable.standalone" android:value="true"/>`.

## Tasks

1. **Config plugin** `scripts/wearModule/withWearModule.ts` — mirrors
   `withAndroidWidget.ts`: create `:wear` module, patch `settings.gradle`,
   add Wear deps (`wear-tiles`, `wear-protolayout`, `wear-protolayout-material3`,
   `compose-wear`, `datastore-preferences`), copy `targets/wear/kotlin/*`,
   generate `strings.xml`, add standalone meta-data. Register in `app.config.ts`
   `plugins` after `withAndroidWidget`.
2. **`ServerStore.kt`** — DataStore `<url, authRequired, username, password, lpIndex>`; done here.
3. **`ApiClient.kt` / `Format.kt` / `Loadpoint.kt`** — ported; done here.
4. **`Theme.kt`** — protolayout ARGB constants; done here.
5. **`LoadpointTileLayout.kt`** — protolayout: title row, big value + unit,
   status line, mode chip row (tappable), reload icon, bottom progress arc.
   Width is fixed on a watch, so one layout (no width classes). — **TODO**
6. **`LoadpointTileService.kt`** — `onTileRequest` builds state via `ApiClient`
   off `ServerStore`; `onTileResourcesRequest` for icons; click handlers POST
   `/api/loadpoints/{i}/mode/{m}` then `getUpdater(...).requestUpdate()`;
   freshness interval 30 min. — **TODO**
7. **`MainActivity.kt` + `ConfigScreen.kt`** — Compose‑for‑Wear: URL field,
   auth toggle + fields, "load loadpoints" → `ApiClient.loadpointTitles`,
   picker, save to `ServerStore`. — **TODO (stub)**
8. **i18n** — extend the #255 xcstrings→strings step to also emit into `:wear`.
9. **CI / EAS** — ensure the `:wear` module compiles in the same Gradle build;
   decide whether to ship the Wear APK embedded (standalone) — yes for v1.
10. **Manual test** — Wear emulator (API 34, `wear` image): place tile, verify
    reachability against a LAN evcc and the gateway, mode switch round‑trips.

## Open questions

- Ship the Wear APK inside the phone APK (standalone install) vs separate track —
  v1: embedded.
- Icon set — reuse the widget's vector drawables, re‑emit into `:wear/res`.
- Min Wear API — target Wear OS 4 (API 33+) for protolayout material3.
