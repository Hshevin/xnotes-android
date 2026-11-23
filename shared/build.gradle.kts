plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

val emojiSource = layout.projectDirectory.file("src/commonMain/data/emoji.tsv")
val emojiKotlin = layout.buildDirectory.dir("generated/emoji/kotlin")
val interopFixtures = layout.projectDirectory.dir("src/commonTest/fixtures/interop")
val fixtureKotlin = layout.buildDirectory.dir("generated/interop-test/kotlin")
val generateInteropFixtures by tasks.registering {
    inputs.dir(interopFixtures)
    outputs.dir(fixtureKotlin)
    doLast {
        val file = fixtureKotlin.get().file("com/xnotes/format/interop/GoldenBytes.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("package com.xnotes.format.interop")
            appendLine("internal val goldenHex = mapOf(")
            for (fixture in interopFixtures.asFile.listFiles()!!.filter { it.isFile && !it.isHidden }.sortedBy { it.name }) {
                val hex = fixture.readBytes().joinToString("") { "%02x".format(it) }
                appendLine("    \"${fixture.name}\" to \"$hex\",")
            }
            appendLine(")")
        })
    }
}
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
        optIn.addAll("kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime", "kotlin.concurrent.atomics.ExperimentalAtomicApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    androidTarget {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain { kotlin.srcDir(generateEmojiAliases) }
        commonTest { kotlin.srcDir("src/commonTestFixtures/kotlin"); kotlin.srcDir(generateInteropFixtures) }
        commonMain.dependencies { api(libs.okio) }
        commonTest.dependencies { implementation(kotlin("test")) }
        androidMain.dependencies { implementation(libs.bundles.commonmark) }
        androidUnitTest.dependencies { implementation(libs.junit); implementation(libs.json) }
    }
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
