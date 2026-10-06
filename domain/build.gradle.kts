plugins {
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvmToolchain(ProjectConfig.JVM_VERSION)

    android {
        compileSdk { version = release(ProjectConfig.Android.COMPILE_SDK) }
        minSdk = ProjectConfig.Android.MIN_SDK
        namespace = "${ProjectConfig.PACKAGE_NAME}.domain"
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        named("desktopTest").dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}
