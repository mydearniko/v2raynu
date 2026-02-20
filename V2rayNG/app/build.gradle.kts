import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.jaredsburrows.license")
}

val versionPropsFile = rootProject.file("version.properties")
val signingPropsFile = rootProject.file("signing.properties")

fun loadVersionProps(): Properties {
    val props = Properties()
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { props.load(it) }
    } else {
        props.setProperty("VERSION_NAME", "2.0.9")
        props.setProperty("VERSION_CODE", "709")
        versionPropsFile.outputStream().use { props.store(it, "Project version") }
    }
    return props
}

fun bumpPatchVersionIfBuildTaskRequested() {
    val shouldBump = gradle.startParameter.taskNames.any {
        val taskName = it.lowercase()
        taskName.contains("assemble") || taskName.contains("bundle")
    }
    if (!shouldBump) return

    val props = loadVersionProps()
    val currentVersionName = props.getProperty("VERSION_NAME") ?: error("Missing VERSION_NAME")
    val match = Regex("""2\.0\.(\d+)""").matchEntire(currentVersionName)
        ?: error("VERSION_NAME must stay in 2.0.x format. Current: $currentVersionName")

    val currentVersionCode = props.getProperty("VERSION_CODE")?.toIntOrNull()
        ?: error("VERSION_CODE must be an integer.")
    val nextVersionName = "2.0.${match.groupValues[1].toInt() + 1}"
    val nextVersionCode = currentVersionCode + 1

    props.setProperty("VERSION_NAME", nextVersionName)
    props.setProperty("VERSION_CODE", nextVersionCode.toString())
    versionPropsFile.outputStream().use { props.store(it, "Auto-updated during build") }
    logger.lifecycle("Auto version bump: $currentVersionName ($currentVersionCode) -> $nextVersionName ($nextVersionCode)")
}

bumpPatchVersionIfBuildTaskRequested()
val appVersionProps = loadVersionProps()
val appVersionCode = appVersionProps.getProperty("VERSION_CODE").toInt()
val appVersionName = appVersionProps.getProperty("VERSION_NAME")
val signingProps = Properties().apply {
    if (!signingPropsFile.exists()) {
        error("Missing signing.properties at ${signingPropsFile.path}.")
    }
    signingPropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 36

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(signingProps.getProperty("STORE_FILE"))
            storePassword = signingProps.getProperty("STORE_PASSWORD")
            keyAlias = signingProps.getProperty("KEY_ALIAS")
            keyPassword = signingProps.getProperty("KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "com.v2ray.ang"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        multiDexEnabled = true

        val abiFilterList = (properties["ABI_FILTERS"] as? String)
            ?.split(';', ',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        splits {
            abi {
                isEnable = true
                reset()
                if (abiFilterList.isNotEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include("arm64-v8a")
                }
                isUniversalApk = false
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            buildConfigField("String", "DISTRIBUTION", "\"F-Droid\"")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        val isFdroid = variant.productFlavors.any { it.name == "fdroid" }
        if (isFdroid) {
            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 2, "arm64-v8a" to 1, "x86" to 4, "x86_64" to 3, "universal" to 0
                )

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = output.getFilter("ABI") ?: "universal"
                    output.outputFileName = "v2rayNG_${variant.versionName}-fdroid_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (100 * variant.versionCode + versionCodes[abi]!!).plus(5000000)
                    } else {
                        return@forEach
                    }
                }
        } else {
            val versionCodes =
                mapOf("armeabi-v7a" to 4, "arm64-v8a" to 4, "x86" to 4, "x86_64" to 4, "universal" to 4)

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = if (output.getFilter("ABI") != null)
                        output.getFilter("ABI")
                    else
                        "universal"

                    output.outputFileName = "v2rayNG_${variant.versionName}_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (1000000 * versionCodes[abi]!!).plus(variant.versionCode)
                    } else {
                        return@forEach
                    }
                }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment)

    // UI Libraries
    implementation(libs.material)
    implementation(libs.toasty)
    implementation(libs.editorkit)
    implementation(libs.flexbox)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Language and Processing Libraries
    implementation(libs.language.base)
    implementation(libs.language.json)

    // Intent and Utility Libraries
    implementation(libs.quickie.foss)
    implementation(libs.core)

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Multidex Support
    implementation(libs.multidex)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
