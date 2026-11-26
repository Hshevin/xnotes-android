plugins { kotlin("multiplatform") }

kotlin {
    listOf(iosArm64() to "iphoneos", iosSimulatorArm64() to "iphonesimulator").forEach { (target, sdk) ->
        target.compilerOptions.freeCompilerArgs.add("-Xoverride-konan-properties=osVersionMin.${target.konanTarget.name}=18.0")
        val script = rootProject.file("tools/ios-native-build.sh")
        val output = layout.buildDirectory.dir("native/$sdk")
        val archives = listOf("libxnotespdf.a", "libxnotests.a").map { name -> output.map { it.file(name) } }
        val buildNative = tasks.register<Exec>("buildNative${target.name.replaceFirstChar { it.uppercase() }}") {
            outputs.files(archives)
            doNotTrackState("Ninja tracks the compiler, SDK, generated manifest and transitive C/C++ headers")
            commandLine("bash", script, sdk, output.get().asFile)
        }
        val bridge = target.compilations.getByName("main").cinterops.create("xnotes") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/xnotes.def"))
            includeDirs(project.file("src/native/include"))
            extraOpts("-libraryPath", output.get().asFile.absolutePath)
        }
        tasks.named(bridge.interopProcessingTaskName) {
            dependsOn(buildNative)
            inputs.files(archives)
            inputs.dir("src/native/include")
        }
    }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("iosTestDevice").orNull?.let { device.set(it) }
}
