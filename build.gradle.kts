import io.gitlab.arturbosch.detekt.Detekt

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(
        files(
            "$rootDir/core-model/src/main/kotlin",
            "$rootDir/core-engine/src/main/kotlin",
            "$rootDir/core-reader/src/main/kotlin",
            "$rootDir/app/src/main/java",
            "$rootDir/app/src/cloud/java",
            "$rootDir/app/src/full/java"
        )
    )
    parallel = true
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(true)
    }
}

val verifyArchitecture by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verify modular boundaries, file size limits, banned APIs, and OkHttp resource closing"
    workingDir = rootDir
    commandLine("python", "$rootDir/scripts/check_architecture.py")
}

val qualityGate by tasks.registering {
    group = "verification"
    description = "Run full static architecture guard, Detekt, JVM tests, and Android Lint"
    dependsOn(
        verifyArchitecture,
        "detekt",
        ":core-model:test",
        ":core-engine:test",
        ":core-reader:test",
        ":app:testCloudDebugUnitTest",
        ":app:lintCloudDebug"
    )
}

val assembleR8Release by tasks.registering {
    group = "build"
    description = "Assemble R8-minified and resource-shrunk release APKs for both cloud and full flavors"
    dependsOn(
        ":app:assembleCloudRelease",
        ":app:assembleFullRelease"
    )
}
