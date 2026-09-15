plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.questtime.vr"
    compileSdk = 34
    ndkVersion = "30.0.16138531"

    defaultConfig {
        applicationId = "com.questtime.vr"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { isJniDebuggable = true }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures { prefab = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric needs the merged resources and the manifest.
        unitTests.isIncludeAndroidResources = true
    }
}

/**
 * The JVM tests are the safety net, and they are only a safety net when their
 * fixtures are present.
 *
 * The sample panoramas and the ffmpeg ground truth are not in the repository, and
 * the tests skip on a JUnit assumption when a fixture is missing rather than
 * failing. That is the right behaviour - a contributor without the files should
 * still be able to run what can be run - but it means a fresh clone reports a
 * green build having checked almost nothing. So say so, in the place people look.
 */
tasks.withType<Test>().configureEach {
    testLogging { events("skipped", "failed") }

    var skipped = 0
    var ran = 0
    afterTest(KotlinClosure2<TestDescriptor, TestResult, Unit>({ _, result ->
        if (result.resultType == TestResult.ResultType.SKIPPED) skipped++ else ran++
        Unit
    }))
    doLast {
        if (skipped > 0) {
            logger.warn(
                "\n  WARNING: $skipped of ${skipped + ran} tests were skipped for missing\n" +
                "  fixtures, so this green build verified very little.\n" +
                "  See reference/README.md, then run reference/make_truth.sh.\n"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.khronos.openxr:openxr_loader_for_android:1.1.63")
    testImplementation("junit:junit:4.13.2")
    // Only so MenuBar can be drawn on the host. Nothing in the app depends on it,
    // and nothing in the decode path needs it - those tests are plain Kotlin.
    testImplementation("org.robolectric:robolectric:4.14.1")
}
