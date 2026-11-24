plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "dev.kaspy.herdr"
 compileSdk = 37
 defaultConfig { applicationId = "dev.kaspy.herdr"; minSdk = 34; targetSdk = 36; versionCode = 69; versionName = "0.69"; ndk { abiFilters += "arm64-v8a" } }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 packaging { dex.useLegacyPackaging = true; resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
dependencies {
 implementation(project(":termlib"))
 implementation(platform("androidx.compose:compose-bom:2026.09.00"))
 implementation("androidx.activity:activity-compose:1.13.0")
 implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.foundation:foundation")
 implementation("com.github.mwiede:jsch:2.28.8")
 implementation("org.bouncycastle:bcprov-jdk18on:1.87")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20250517")
}
