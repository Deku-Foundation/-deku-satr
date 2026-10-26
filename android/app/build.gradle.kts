import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}


// Stable, development-only signer lets successive CI debug APKs update in place.
// Never use this public key for a production/release build.
val stableDebugKeystore = layout.buildDirectory.file("signing/satr-dev-debug.keystore")
val prepareStableDebugKeystore by tasks.registering {
    val encodedKey = layout.projectDirectory.file("ci-signing/satr-dev-debug.keystore.b64").asFile
    inputs.file(encodedKey)
    outputs.file(stableDebugKeystore)
    doLast {
        val output = stableDebugKeystore.get().asFile
        output.parentFile.mkdirs()
        val encoded = encodedKey.readText().filterNot(Char::isWhitespace)
        output.writeBytes(Base64.getDecoder().decode(encoded))
    }
}

android {
    namespace = "org.dekufoundation.satr"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.dekufoundation.satr"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.6.2"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    signingConfigs {
        create("stableDev") {
            storeFile = stableDebugKeystore.get().asFile
            storeType = "JKS"
            storePassword = "satr-dev-only-2026"
            keyAlias = "satr-debug"
            keyPassword = "satr-dev-only-2026"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableDev")
        }
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


tasks.named("preBuild").configure { dependsOn(prepareStableDebugKeystore) }

val rtmdetAssetsDirectory = layout.buildDirectory.dir("generated/rtmdetAssets")
android.sourceSets.getByName("main").assets.srcDir(rtmdetAssetsDirectory.get().asFile)
val rtmdetModelFile = rtmdetAssetsDirectory.map { it.file("rtmdet-ins_tiny_640x640.onnx") }
val rtmdetLicenseFile = rtmdetAssetsDirectory.map { it.file("RTMDET_INS_LICENSE") }
val rtmdetNoticeFile = rtmdetAssetsDirectory.map { it.file("RTMDET_INS_NOTICE.md") }

val downloadRtmdetAssets by tasks.registering {
    outputs.files(rtmdetModelFile, rtmdetLicenseFile, rtmdetNoticeFile)
    outputs.upToDateWhen { false }
    doLast {
        val model = rtmdetModelFile.get().asFile
        val license = rtmdetLicenseFile.get().asFile
        val notice = rtmdetNoticeFile.get().asFile
        model.parentFile.mkdirs()
        // A local incremental build may still contain the former 43 MB S variant.
        File(model.parentFile, "rtmdet-ins_s_640x640.onnx").delete()
        fun digest(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        fun download(url: String, destination: File) {
            val temporary = File(destination.parentFile, destination.name + ".download")
            try {
                URL(url).openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 120_000
                }.getInputStream().use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                if (destination.exists() && !destination.delete()) {
                    throw GradleException("Cannot replace " + destination.name)
                }
                if (!temporary.renameTo(destination)) {
                    throw GradleException("Cannot install " + destination.name)
                }
            } finally {
                temporary.delete()
            }
        }

        val revision = "e5218e8d47d7c50077f27f9893a28e0ef2ad5656"
        if (!model.isFile || model.length() != 24_032_356L ||
            digest(model) != "99ee670820c4493aa13e20578b6a8e39781c319846dc41796a77765734beac5c"
        ) {
            download(
                "https://huggingface.co/tori29umai/rtmdet-ins-onnx-person-masks/resolve/" + revision + "/models/tiny/rtmdet-ins_tiny_640x640.onnx",
                model,
            )
        }
        check(model.length() == 24_032_356L &&
            digest(model) == "99ee670820c4493aa13e20578b6a8e39781c319846dc41796a77765734beac5c"
        ) { "RTMDet-Ins-Tiny asset failed size/SHA-256 verification." }

        if (!license.isFile || license.length() == 0L) {
            download(
                "https://huggingface.co/tori29umai/rtmdet-ins-onnx-person-masks/resolve/" + revision + "/LICENSE",
                license,
            )
        }
        if (!notice.isFile || notice.length() == 0L) {
            download(
                "https://huggingface.co/tori29umai/rtmdet-ins-onnx-person-masks/resolve/" + revision + "/NOTICE.md",
                notice,
            )
        }
        check(license.readText().contains("Apache License")) { "RTMDet license text is missing." }
        check(notice.length() > 0L) { "RTMDet NOTICE file is empty." }
    }
}
tasks.named("preBuild").configure { dependsOn(downloadRtmdetAssets) }

val facePresentationAssetsDirectory = layout.buildDirectory.dir("generated/facePresentationAssets")
android.sourceSets.getByName("main").assets.srcDir(facePresentationAssetsDirectory.get().asFile)
val yuNetModelFile = facePresentationAssetsDirectory.map { it.file("face_detection_yunet_2023mar.onnx") }
val fastFaceModelFile = facePresentationAssetsDirectory.map { it.file("fastface-large-128-int8.onnx") }
val fastFaceLicenseFile = facePresentationAssetsDirectory.map { it.file("FASTFACE_LICENSE") }
val yuNetLicenseFile = facePresentationAssetsDirectory.map { it.file("YUNET_LICENSE") }

val downloadFacePresentationAssets by tasks.registering {
    outputs.files(yuNetModelFile, fastFaceModelFile, fastFaceLicenseFile, yuNetLicenseFile)
    outputs.upToDateWhen { false }
    doLast {
        val yuNet = yuNetModelFile.get().asFile
        val fastFace = fastFaceModelFile.get().asFile
        val fastFaceLicense = fastFaceLicenseFile.get().asFile
        val yuNetLicense = yuNetLicenseFile.get().asFile
        yuNet.parentFile.mkdirs()

        fun digest(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        fun download(url: String, destination: File) {
            val temporary = File(destination.parentFile, destination.name + ".download")
            try {
                URL(url).openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 120_000
                }.getInputStream().use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                if (destination.exists() && !destination.delete()) {
                    throw GradleException("Cannot replace " + destination.name)
                }
                if (!temporary.renameTo(destination)) {
                    throw GradleException("Cannot install " + destination.name)
                }
            } finally {
                temporary.delete()
            }
        }

        val fastFaceRevision = "15240f03bb257385305e5985e04e6d2477a242fa"
        if (!fastFace.isFile || fastFace.length() != 4_008_918L ||
            digest(fastFace) != "2834e70884800019972e588fbe95eb73ebe942120a364d8aaa60085ba4f5b9cf"
        ) {
            download(
                "https://huggingface.co/iFurySt/fastface/resolve/" + fastFaceRevision + "/models/fastface-large-128/model_int8_static.onnx",
                fastFace,
            )
        }
        check(fastFace.length() == 4_008_918L &&
            digest(fastFace) == "2834e70884800019972e588fbe95eb73ebe942120a364d8aaa60085ba4f5b9cf"
        ) { "FastFace gender model failed size/SHA-256 verification." }

        val yuNetRevision = "48b10fa505aeb51fbd127fc60a5c660040e823b7"
        if (!yuNet.isFile || yuNet.length() != 232_589L ||
            digest(yuNet) != "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
        ) {
            download(
                "https://github.com/opencv/opencv_zoo/raw/" + yuNetRevision + "/models/face_detection_yunet/face_detection_yunet_2023mar.onnx",
                yuNet,
            )
        }
        check(yuNet.length() == 232_589L &&
            digest(yuNet) == "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
        ) { "YuNet face detector failed size/SHA-256 verification." }

        if (!fastFaceLicense.isFile || fastFaceLicense.length() == 0L) {
            download(
                "https://raw.githubusercontent.com/iFurySt/fastface/cd8b24ce3de4f967332b9a7613e047bfe37368a7/LICENSE",
                fastFaceLicense,
            )
        }
        if (!yuNetLicense.isFile || yuNetLicense.length() == 0L) {
            download(
                "https://raw.githubusercontent.com/opencv/opencv_zoo/" + yuNetRevision + "/models/face_detection_yunet/LICENSE",
                yuNetLicense,
            )
        }
        check(fastFaceLicense.readText().contains("Apache License")) { "FastFace Apache-2.0 license is missing." }
        check(yuNetLicense.readText().contains("MIT License")) { "YuNet MIT license is missing." }
    }
}
tasks.named("preBuild").configure { dependsOn(downloadFacePresentationAssets) }





dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
}
