plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    // Applied in :app by the Firebase wiring task once app/google-services.json exists.
    alias(libs.plugins.google.services) apply false
}
