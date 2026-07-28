import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.chaquo.python")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val releaseSigningPropertiesFile =
    System.getenv("PHOTOBOOK_SIGNING_PROPERTIES")
        ?.takeIf { it.isNotBlank() }
        ?.let { file(it) }
val releaseSigningProperties = Properties()
val releaseBuildRequested =
    gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
val supportedAbis = setOf("arm64-v8a", "x86_64")
val targetAbis =
    System.getenv("PHOTOBOOK_TARGET_ABIS")
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.distinct()
        ?.takeIf(List<String>::isNotEmpty)
        ?: listOf("arm64-v8a", "x86_64")

if (targetAbis.any { it !in supportedAbis }) {
    throw GradleException(
        "PHOTOBOOK_TARGET_ABIS 只支持 ${supportedAbis.joinToString()}，当前为 ${targetAbis.joinToString()}",
    )
}

if (releaseSigningPropertiesFile?.isFile == true) {
    releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
} else if (releaseBuildRequested) {
    throw GradleException(
        "Release 构建必须通过 PHOTOBOOK_SIGNING_PROPERTIES 指定 key.properties。",
    )
}

fun releaseSigningProperty(name: String): String =
    releaseSigningProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("Release 签名配置缺少 $name")

fun resolveBuildPython(): String {
    val configured = System.getenv("PHOTOBOOK_PYTHON")
    if (!configured.isNullOrBlank()) return configured
    return runCatching {
        val process =
            ProcessBuilder("uv", "python", "find", "3.13")
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0 && output.isNotEmpty())
        output
    }.getOrDefault("python3.13")
}

android {
    namespace = "com.mantou.photobook"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.mantou.photobook"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        ndk {
            abiFilters += targetAbis
        }
    }

    signingConfigs {
        if (releaseSigningPropertiesFile?.isFile == true) {
            create("release") {
                val configuredStoreFile = file(releaseSigningProperty("storeFile"))
                if (!configuredStoreFile.isFile) {
                    throw GradleException("Release 密钥库不存在：$configuredStoreFile")
                }
                storeFile = configuredStoreFile
                storePassword = releaseSigningProperty("storePassword")
                keyAlias = releaseSigningProperty("keyAlias")
                keyPassword = releaseSigningProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            proguardFiles("proguard-rules.pro")
        }
        configureEach {
            ndk.abiFilters.clear()
            ndk.abiFilters.addAll(targetAbis)
        }
    }

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython(resolveBuildPython())
        pip {
            install("../python/wheels/instaloader-4.15.2-py3-none-any.whl")
            install("requests==2.34.2")
            install("charset-normalizer==3.4.9")
            install("idna==3.18")
            install("urllib3==2.7.0")
            install("certifi==2026.7.22")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("io.minio:minio:9.0.3") {
        // These codecs are only used by MinIO Snowball uploads, which PhotoBook never calls.
        exclude(group = "org.apache.commons", module = "commons-compress")
        exclude(group = "org.xerial.snappy", module = "snappy-java")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
