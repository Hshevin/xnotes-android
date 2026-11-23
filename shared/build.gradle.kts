plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

val emojiSource = layout.projectDirectory.file("src/commonMain/data/emoji.tsv")
val emojiKotlin = layout.buildDirectory.dir("generated/emoji/kotlin")
val generateEmojiAliases by tasks.registering {
    inputs.file(emojiSource)
    outputs.dir(emojiKotlin)
    doLast {
        val target = emojiKotlin.get().file("com/xnotes/core/text/EmojiAliases.kt").asFile
        target.parentFile.mkdirs()
        val aliases = emojiSource.asFile.readText()
        require('"' !in aliases && '$' !in aliases)
        target.writeText("package com.xnotes.core.text\n\ninternal const val emojiAliases = \"\"\"$aliases\"\"\"\n")
    }
}

kotlin {
    compilerOptions {
        optIn.addAll("kotlin.uuid.ExperimentalUuidApi", "kotlin.concurrent.atomics.ExperimentalAtomicApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    androidTarget {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain { kotlin.srcDir(generateEmojiAliases) }
        commonTest { kotlin.srcDir("src/commonTestFixtures/kotlin") }
        commonMain.dependencies { api(libs.okio) }
        commonTest.dependencies { implementation(kotlin("test")) }
        androidMain.dependencies { implementation(libs.bundles.commonmark) }
        androidUnitTest.dependencies { implementation(libs.junit) }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    standalone.set(false)
    providers.gradleProperty("iosTestDevice").orNull?.let { device.set(it) }
}

android {
    namespace = "com.xnotes.shared"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig.minSdk = 26
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
