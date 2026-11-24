import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Compile the pinned upstream JVM/resources locally, but reuse the official
// native release payload verbatim so this build needs neither an NDK nor CMake.
val upstreamNative = configurations.create("upstreamNative") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies.add(upstreamNative.name, "org.connectbot:termlib:0.3.15@aar")

@CacheableTask
abstract class ExtractTermlibNative : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val releaseAar: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val aar = releaseAar.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(aar.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == "85276764f139bc8457918dbfff8a4e3fd22da9b500e4569d2d882f37d67f8e57") {
            "The official ConnectBot Termlib AAR checksum did not match 0.3.15."
        }
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        ZipFile(aar).use { zip ->
            // Match the app's supported ABI, not all four upstream ABIs.
            for (name in listOf("libjni_cb_term.so")) {
                val entry = checkNotNull(zip.getEntry("jni/arm64-v8a/$name"))
                val target = output.resolve("arm64-v8a/$name")
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }
}

val extractPinnedTermlibNative = tasks.register<ExtractTermlibNative>("extractPinnedTermlibNative") {
    releaseAar.set(layout.file(upstreamNative.elements.map { it.single().asFile }))
    outputDirectory.set(layout.buildDirectory.dir("generated/upstreamJni"))
}

android {
    namespace = "org.connectbot.terminal"
    compileSdk = 37
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { jniLibs.keepDebugSymbols.add("**/*.so") }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            extractPinnedTermlibNative, ExtractTermlibNative::outputDirectory,
        )
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.20")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    testImplementation("junit:junit:4.13.2")
}

// Match the app's AndroidX runtime when compiling the stand-alone library.
// Otherwise older transitive versions from AppCompat can enter its own variants.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        when (requested.group) {
            "androidx.activity" -> useVersion("1.13.0")
            "androidx.lifecycle" -> useVersion("2.11.0")
            "androidx.savedstate" -> useVersion("1.4.0")
        }
    }
}
