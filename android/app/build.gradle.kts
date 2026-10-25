import java.io.File
import java.net.URL
import java.security.MessageDigest
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.dekufoundation.satr"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.dekufoundation.satr"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.5.1"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        release {
            // Keep shrinking off until a signed release build is independently verified.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}


val lrasppModelFile = layout.buildDirectory.file(
    "generated/lrasppAssets/lraspp_mobilenet_v3_large_dynamic_wi8_afp32.tflite",
)
android.sourceSets.getByName("main").assets.srcDir(
    layout.buildDirectory.dir("generated/lrasppAssets"),
)

val downloadLrasppModel by tasks.registering {
    outputs.file(lrasppModelFile)
    doLast {
        val output = lrasppModelFile.get().asFile
        fun digest(file: java.io.File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val bytes = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(bytes)
                    if (read < 0) break
                    md.update(bytes, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        if (output.isFile && digest(output) == "1a0a31a925f1c8e2abb76e96c4bd4bdcbc625f8389f1029530007fded4ea9ed9") {
            return@doLast
        }
        output.parentFile.mkdirs()
        val temporary = File(output.parentFile, output.name + ".download")
        try {
            URL("https://huggingface.co/litert-community/lraspp_mobilenet_v3_large/resolve/ac7d12fd05b7542cd884c85968b3193c8f8e4fa7/lraspp_mobilenet_v3_large_dynamic_wi8_afp32.tflite")
                .openConnection()
                .apply {
                    connectTimeout = 20_000
                    readTimeout = 90_000
                }
                .getInputStream()
                .use { input -> temporary.outputStream().use { outputStream -> input.copyTo(outputStream) } }
            val actual = digest(temporary)
            if (actual != "1a0a31a925f1c8e2abb76e96c4bd4bdcbc625f8389f1029530007fded4ea9ed9") {
                throw GradleException("LR-ASPP model checksum mismatch: $actual")
            }
            if (output.exists() && !output.delete()) throw GradleException("Cannot replace the stale LR-ASPP model.")
            if (!temporary.renameTo(output)) throw GradleException("Cannot install the downloaded LR-ASPP model.")
        } finally {
            temporary.delete()
        }
    }
}
tasks.named("preBuild").configure { dependsOn(downloadLrasppModel) }


dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.litert.interpreter)
    testImplementation(libs.junit)
}
