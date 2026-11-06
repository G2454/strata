import java.time.Duration

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.strata.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.strata.player"
        minSdk = 26
        targetSdk = 35
        // Every GitHub build gets a higher number, so Android sees it as an update (and Settings shows which build it is).
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = build ?: 1
        versionName = if (build != null) "2.0.0 (build $build)" else "2.0.0 (local)"
    }

    // One permanent signing key, kept as GitHub secrets (see .github/workflows/build.yml). Without it Android
    // refuses to install a new build over the old one, and uninstalling first deletes playlists and play counts.
    val keystore = System.getenv("STRATA_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("strata") {
                storeFile = keystore
                storePassword = System.getenv("STRATA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("STRATA_KEY_ALIAS") ?: "strata"
                keyPassword = System.getenv("STRATA_KEY_PASSWORD") ?: System.getenv("STRATA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the secrets (a fork, or a local build) it falls back to the debug key so the APK still installs.
            signingConfig = signingConfigs.findByName("strata") ?: signingConfigs.getByName("debug")
        }
        debug {
            // Same key, so the debug APK can also be installed over the release one (and back).
            signingConfigs.findByName("strata")?.let { signingConfig = it }
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
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("com.google.guava:guava:33.3.1-android")

    // Tests: JVM logic tests + Compose UI tests on Robolectric (real Android framework, no emulator needed).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.espresso:espresso-core:3.6.1")
    // README screenshots (ScreenshotTest, run with -Pscreenshots)
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.39.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.39.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    // Each test class gets a fresh JVM: Robolectric, Compose and ExoPlayer keep global state
    // (threads, loopers, snapshot observers) that can leak from one class into the next.
    forkEvery = 1
    // Never let a stuck test eat the whole CI job.
    timeout.set(Duration.ofMinutes(25))
    // Screenshots for the README are only made on request: ./gradlew testDebugUnitTest -Pscreenshots
    if (project.hasProperty("screenshots")) {
        systemProperty("roborazzi.test.record", "true")
        systemProperty("strata.screenshots", rootProject.file("out/screenshots").absolutePath)
        filter { includeTestsMatching("*ScreenshotTest") }
    } else {
        exclude("**/ScreenshotTest*")
    }
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Print results as GitHub Actions annotations so they show on the run page without opening logs.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(desc: TestDescriptor, result: TestResult) {
            if (result.resultType != TestResult.ResultType.FAILURE) return
            val e = result.exception
            val where = e?.stackTrace?.firstOrNull { it.className.startsWith("com.strata") }
                ?.let { " @ ${it.fileName}:${it.lineNumber}" } ?: ""
            val msg = ((e?.javaClass?.simpleName ?: "Failure") + ": " + (e?.message ?: "") + where)
                .replace("\r", " ").replace("\n", " | ").take(4000)
            println("::error title=${desc.className?.substringAfterLast('.')}.${desc.name}::$msg")
        }
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                println("::notice title=Strata tests::${result.resultType} - ${result.testCount} tests, ${result.successfulTestCount} passed, ${result.failedTestCount} failed")
            }
        }
    })
}

// Every APK build runs the full test suite first, so a broken button can't ship.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn("testDebugUnitTest")
}
