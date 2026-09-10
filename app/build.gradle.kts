plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * The version is derived from the git history rather than edited by hand, because a build now
 * has to be identifiable after the fact: a ride trace records the version that produced it, and
 * every build calling itself "1.0" made a captured trip impossible to attribute.
 *
 * The build id is the commit count, so it is sequential, monotonic on `main`, and identical for
 * anyone who builds the same commit. The short SHA names the commit and a `.dirty` suffix marks
 * a tree with uncommitted changes, so a trace can never quietly claim to come from a commit that
 * does not contain what produced it.
 */
fun gitOutput(vararg command: String): String? = runCatching {
    val execution = providers.exec {
        commandLine(*command)
        isIgnoreExitValue = true
    }
    execution
        .takeIf { it.result.get().exitValue == 0 }
        ?.standardOutput?.asText?.get()?.trim()
        ?.takeIf { it.isNotEmpty() }
}.getOrNull()

val baseVersion = "1.0"

// Zero when there is no git history to count, which a source archive or a shallow CI clone has.
// The version then says so rather than inventing a number: CI checks out with fetch-depth 0.
val buildId: Int = gitOutput("git", "rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0
val commitSha: String? = gitOutput("git", "rev-parse", "--short=8", "HEAD")
val isTreeDirty: Boolean = gitOutput("git", "status", "--porcelain") != null

val appVersionName: String = buildString {
    append(baseVersion).append('.').append(buildId)
    commitSha?.let { append('+').append(it) }
    if (isTreeDirty) append(".dirty")
}

android {
    namespace = "com.taxiinspector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.taxiinspector"
        minSdk = 24
        targetSdk = 35
        // Monotonic on main. A shallow clone counts one commit and would produce a lower code
        // than an installed build, which Android refuses to install over; build from full
        // history when the artifact is going on a device that already has one.
        versionCode = buildId.coerceAtLeast(1)
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            // Every trip on a debug build writes a ride trace, coordinates included, so the
            // app's own billing decisions can be compared with an independent recording.
            buildConfigField("boolean", "RIDE_TRACE_ENABLED", "true")
        }
        getByName("release") {
            // Compiled out, so the release privacy contract holds: no route is ever stored.
            buildConfigField("boolean", "RIDE_TRACE_ENABLED", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    // scripts/replay-trace.sh passes a captured decisions.csv here; TraceReplayTest is
    // skipped when the property is absent.
    System.getProperty("taxi.trace")?.let { systemProperty("taxi.trace", it) }
}
