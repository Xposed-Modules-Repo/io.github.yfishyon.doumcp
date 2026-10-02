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

android {
    namespace = "io.github.yfishyon.doumcp"
    buildToolsVersion = "37.0.0"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = gitHash
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs["debug"]
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
