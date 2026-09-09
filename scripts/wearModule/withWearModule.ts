import {
  ConfigPlugin,
  withDangerousMod,
  withSettingsGradle,
  withAndroidManifest,
  AndroidConfig,
} from "expo/config-plugins";
import fs from "fs";
import path from "path";

/**
 * Adds a standalone Wear OS module (`:wear`) with a single loadpoint Tile to the
 * prebuilt Android project. Sibling of scripts/androidWidget/withAndroidWidget.ts
 * but a whole APK, not a widget inside the phone app.
 *
 * `expo prebuild` stays the only step: this plugin scaffolds android/wear/,
 * wires settings.gradle, copies the Kotlin sources from targets/wear/kotlin,
 * generates strings.xml, and marks the phone app as Wear-standalone-capable.
 *
 * STATUS: skeleton. See targets/wear/PLAN.md task 1.
 */

const KOTLIN_SRC = "targets/wear/kotlin";
const WEAR_PACKAGE = "io.evcc.wear";
const WEAR_DIR = "android/wear"; // gitignored like android/, regenerated
const XCSTRINGS = "targets/widget/Localizable.xcstrings"; // reuse #255 catalog

// Pin against the versions Wear OS 4 / protolayout material3 need. Bump together.
const WEAR = {
  tiles: "1.4.1",
  protolayout: "1.2.1",
  protolayoutMaterial3: "1.2.1",
  composeWear: "1.4.1",
  datastore: "1.1.1",
  guava: "33.3.1-android",
};

/** 1. `include(":wear")` in settings.gradle. */
const withWearInSettings: ConfigPlugin = (config) =>
  withSettingsGradle(config, (config) => {
    if (!config.modResults.contents.includes('include(":wear")')) {
      config.modResults.contents +=
        '\ninclude(":wear")\nproject(":wear").projectDir = new File(rootProject.projectDir, "wear")\n';
    }
    return config;
  });

/** 2. Phone app declares itself Wear-standalone-capable so the watch APK installs on its own. */
const withStandaloneMeta: ConfigPlugin = (config) =>
  withAndroidManifest(config, (config) => {
    const app = AndroidConfig.Manifest.getMainApplicationOrThrow(config.modResults);
    AndroidConfig.Manifest.addMetaDataItemToMainApplication(
      app,
      "com.google.android.wearable.standalone",
      "true",
    );
    return config;
  });

/**
 * 3. Scaffold android/wear/: build.gradle, AndroidManifest.xml (TileService +
 *    MainActivity), copy Kotlin, generate res/values*/strings.xml from the
 *    xcstrings catalog (same generator as withAndroidWidget), copy icons.
 */
const withWearProject: ConfigPlugin = (config) =>
  withDangerousMod(config, [
    "android",
    async (config) => {
      const root = config.modRequest.projectRoot;
      const wear = path.join(root, WEAR_DIR);
      const kotlinOut = path.join(
        wear,
        "src/main/java",
        ...WEAR_PACKAGE.split("."),
      );
      fs.mkdirSync(kotlinOut, { recursive: true });

      // 3a. copy Kotlin sources
      const srcDir = path.join(root, KOTLIN_SRC);
      for (const f of fs.readdirSync(srcDir).filter((f) => f.endsWith(".kt"))) {
        fs.copyFileSync(path.join(srcDir, f), path.join(kotlinOut, f));
      }

      // 3b. build.gradle  — TODO
      //   plugins { id "com.android.application"; id "org.jetbrains.kotlin.android"; id "org.jetbrains.kotlin.plugin.compose" }
      //   android { namespace "io.evcc.wear"; defaultConfig { minSdk 33; applicationId "io.evcc.android" ... } buildFeatures { compose true } }
      //   dependencies { wear-tiles, wear-protolayout, wear-protolayout-material3, compose-wear, datastore-preferences, guava, kotlinx-coroutines-guava }

      // 3c. AndroidManifest.xml — TODO
      //   <uses-feature android:name="android.hardware.type.watch" />
      //   <application> <service LoadpointTileService, permission BIND_TILE_PROVIDER, intent-filter TileProvider,
      //     meta-data androidx.wear.tiles.PREVIEW> ; <activity MainActivity, LAUNCHER>

      // 3d. res/values*/strings.xml from XCSTRINGS — reuse withAndroidWidget's
      //     xcstrings parser; emit widget_* keys used by LoadpointTileLayout.

      // 3e. icons — re-emit the widget vector drawables into wear/res/drawable.

      return config;
    },
  ]);

const withWearModule: ConfigPlugin = (config) => {
  config = withWearInSettings(config);
  config = withStandaloneMeta(config);
  config = withWearProject(config);
  return config;
};

export default withWearModule;
