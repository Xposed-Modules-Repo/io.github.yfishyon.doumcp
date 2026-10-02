import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
}

val gitHash =
    providers
        .exec {
            commandLine("git", "rev-parse", "--short=7", "HEAD")
        }.standardOutput.asText
        .get()
        .trim()

// 签名信息读取自根目录 release-signing.properties（不入库），缺失时 release 不签名
val releaseSigning =
    rootProject.file("release-signing.properties").takeIf { it.exists() }?.let { props ->
        Properties().apply { props.inputStream().use { load(it) } }
    }

android {
    namespace = "io.github.yfishyon.doumcp"
    buildToolsVersion = "37.0.0"
    compileSdk = 37

    if (releaseSigning != null) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = releaseSigning?.let { signingConfigs["release"] }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.mcp.sdk.server)
    implementation(libs.ktor.server.cio)
    implementation(libs.dexkit)
    implementation(libs.fastkv)
}
