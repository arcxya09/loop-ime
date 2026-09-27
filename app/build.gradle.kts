plugins { id("com.android.application") }
providers.gradleProperty("loopBuildRoot").orNull?.let { layout.buildDirectory.set(file("$it/app")) }
android {
    namespace = "app.loop.ime"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    buildToolsVersion = "37.0.0"
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file("signing/loop-development.jks")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
    defaultConfig {
        applicationId = "app.loop.ime"
        minSdk = 37
        targetSdk = 37
        versionCode = 16
        versionName = "0.1.15-alpha.16"
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = providers.gradleProperty("loopTestRunner")
            .orElse("app.loop.ime.LoopInstrumentation").get()
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs { useLegacyPackaging = false }; resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    androidResources { noCompress += listOf("onnx", "vocab") }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric's Android 17 runtime accesses JDK 21 file descriptors during app setup.
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}
dependencies {
    implementation("androidx.core:core:1.18.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("net.zetetic:sqlcipher-android:4.10.0@aar")
    implementation("androidx.sqlite:sqlite:2.5.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.sqlite:sqlite-framework:2.5.2")
}
