plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.compose") version "1.9.3"
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.compilerOptions.freeCompilerArgs.add("-Xoverride-konan-properties=osVersionMin.${target.konanTarget.name}=18.0")
        target.binaries.framework {
            baseName = "XNotesKit"
            isStatic = true
            binaryOption("bundleId", "com.xnotes.ipad.kit")
        }
    }
    sourceSets.iosMain.dependencies {
        implementation(project(":shared"))
        implementation(project(":nativelibs"))
        implementation(compose.foundation)
    }
}
