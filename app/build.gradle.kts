import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Optional convenience seed for the AeroAPI key on local builds: read from a
// gitignored local.properties (AERO_API_KEY=…) or the environment. Defaults to
// empty so the key is NOT baked into the APK unless you explicitly opt in — the
// secure, primary path is to paste the key into the app's Tuning screen, where
// it is stored in Android Keystore-backed EncryptedSharedPreferences only.
val aeroApiKeySeed: String = run {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { props.load(it) }
    props.getProperty("AERO_API_KEY") ?: System.getenv("AERO_API_KEY") ?: ""
}

android {
    namespace = "com.sam.airblock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sam.airblock"
        minSdk = 31
        targetSdk = 35
        versionCode = 28
        versionName = "6.0"

        // Seed only — the running key lives in EncryptedSharedPreferences. Empty
        // unless AERO_API_KEY is set in local.properties / the environment.
        buildConfigField("String", "AERO_API_KEY", "\"$aeroApiKeySeed\"")
    }

    // CI signing: AGP kept regenerating a fresh debug key on every runner even
    // though the workflow restored ~/.android/debug.keystore (three releases,
    // three different certs -> INSTALL_FAILED_UPDATE_INCOMPATIBLE on update).
    // Taking the path from the env removes every assumption about where AGP
    // looks; locally nothing changes (env unset -> default debug keystore).
    System.getenv("DEBUG_KEYSTORE_FILE")?.let { path ->
        val ks = file(path)
        if (ks.exists()) {
            signingConfigs.getByName("debug") {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Personal app: debug-key signing keeps release APKs installable.
            // CI restores a persistent keystore from DEBUG_KEYSTORE_B64 when set.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // The androidx 1.5/1.11-alpha artifacts bundle lint checks built for a
        // newer lint than AGP 8.7's; the NullSafeMutableLiveData detector
        // crashes the whole lintVitalRelease run (IncompatibleClassChangeError).
        // The app has no LiveData, so the check is pure loss. Remove when AGP
        // is new enough to load those jars natively.
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    // Widget UI - compiles to RemoteViews, no runtime overhead
    implementation("androidx.glance:glance-appwidget:1.2.0-rc01")
    implementation("androidx.glance:glance-material3:1.2.0-rc01")

    // Settings screen (plain Compose, only loaded when the activity opens).
    // material3 1.5.0-alphaXX is the M3 Expressive line: MaterialExpressiveTheme,
    // MotionScheme (spring physics), ButtonGroup/ToggleButton, LoadingIndicator,
    // wavy progress, MaterialShapes, flexible top app bars. alpha18 is the last
    // build that compiles against compileSdk 35 / AGP 8.x.
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    // material3 no longer pulls the icon set transitively
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    // Pin a modern fragment: a stale transitive version trips the
    // InvalidFragmentVersionForActivityResult lint check on release builds
    implementation("androidx.fragment:fragment:1.8.5")

    // Data + networking - deliberately minimal
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Android Keystore-backed storage for the one secret we hold (AeroAPI key)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Location (fused provider)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
