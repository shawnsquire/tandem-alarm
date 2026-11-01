plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "me.shawnsquire.tandem"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.shawnsquire.tandem"
        minSdk = 33
        targetSdk = 37
        // The release workflow sets these from the tag and run number; local builds use the defaults.
        versionCode = providers.environmentVariable("TANDEM_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("TANDEM_VERSION_NAME").orNull ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val emulatorHost = providers.gradleProperty("tandem.emulatorHost").getOrElse("10.0.2.2")
        buildConfigField("boolean", "USE_EMULATOR", "false")
        buildConfigField("String", "EMULATOR_HOST", "\"$emulatorHost\"")
    }

    // Signing comes from the environment so no key material lives in the repo. Without all four
    // variables the release build stays unsigned.
    val signingEnv = listOf(
        "TANDEM_KEYSTORE_PATH",
        "TANDEM_KEYSTORE_PASSWORD",
        "TANDEM_KEY_ALIAS",
        "TANDEM_KEY_PASSWORD",
    ).associateWith { providers.environmentVariable(it).orNull }
    val releaseSigning = if (signingEnv.values.all { !it.isNullOrBlank() }) {
        signingConfigs.create("release") {
            storeFile = file(signingEnv.getValue("TANDEM_KEYSTORE_PATH")!!)
            storePassword = signingEnv.getValue("TANDEM_KEYSTORE_PASSWORD")
            keyAlias = signingEnv.getValue("TANDEM_KEY_ALIAS")
            keyPassword = signingEnv.getValue("TANDEM_KEY_PASSWORD")
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning
        }
        debug {
            buildConfigField("boolean", "USE_EMULATOR", "true")
        }
        // A second debug install (.partner) so one emulator can host both devices of a pair.
        create("partner") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".partner"
            matchingFallbacks += listOf("debug")
        }
    }

    sourceSets {
        // Reuse the debug manifest (cleartext to the emulator host) and resources.
        getByName("partner") {
            manifest.srcFile("src/debug/AndroidManifest.xml")
            res.srcDirs("src/debug/res")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric's SDK 36 sandbox reaches into JDK internals (FileDescriptor).
        unitTests.all {
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.storage)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)

    // Smoke tests against the Firebase emulators (connectedDebugAndroidTest).
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
