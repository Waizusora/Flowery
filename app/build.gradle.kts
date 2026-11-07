import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingStorePath = System.getenv("FLOWERY_KEYSTORE_PATH")
val signingStorePassword = System.getenv("KEYSTORE_PASSWORD")
val signingKeyAlias = System.getenv("KEY_ALIAS")
val signingKeyPassword = System.getenv("KEY_PASSWORD")

// Pin the upstream font source to an immutable Google Fonts commit.
val notoSansRevision = "86aced91de05cd4f96a06838124cca45be337b2b"
val generatedNotoRes = layout.buildDirectory.dir("generated/noto-res/main")
val notoSansFile = generatedNotoRes.map { it.file("font/noto_sans.ttf") }

val downloadNotoSans by tasks.registering {
    val outputFile = notoSansFile
    outputs.file(outputFile)
    outputs.upToDateWhen { outputFile.get().asFile.isFile && outputFile.get().asFile.length() > 100_000 }
    doLast {
        val destination = outputFile.get().asFile
        if (!destination.isFile || destination.length() <= 100_000) {
            destination.parentFile.mkdirs()
            val source = URL("https://raw.githubusercontent.com/google/fonts/$notoSansRevision/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf")
            val temporary = destination.resolveSibling(destination.name + ".download")
            source.openStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
            require(temporary.length() > 100_000) { "Downloaded Noto Sans font is unexpectedly small." }
            check(temporary.renameTo(destination)) { "Unable to install the Noto Sans font resource." }
        }
    }
}

android {
    namespace = "com.waizusora.flowery"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    sourceSets.getByName("main").res.srcDir(generatedNotoRes)

    defaultConfig {
        applicationId = "com.waizusora.flowery"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        create("release") {
            if (!signingStorePath.isNullOrBlank()) {
                storeFile = file(signingStorePath)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (!signingStorePath.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging { jniLibs.useLegacyPackaging = false }
}

tasks.named("preBuild").configure { dependsOn(downloadNotoSans) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media:media:1.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
