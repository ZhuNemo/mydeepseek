import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin 支持，不需要 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Release 签名。
 *
 * 口令放在项目根的 `keystore.properties`（**不在版本库、不要外传**）。
 * 文件不存在时回落到 debug 签名 —— 保证 clone 下来的人也能 `assembleRelease`
 * 跑通流程，而不是卡在一个"缺密钥"的报错上。
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

android {
    namespace = "com.simplechat.app"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        /*
         * ⚠️ 应用身份（安装包名）。
         *
         * 与上面的 `namespace` 刻意不同：namespace 只决定 R 类与源码包路径，
         * 改了要动几百个文件的 package 声明；applicationId 只决定"这是哪个应用"，
         * 换掉就与旧版 / 上游彻底分开，而代码一行都不用动。
         * FileProvider 的 authority 用的是 `${applicationId}.fileprovider`，会自动跟随。
         */
        applicationId = "com.zhunemo.mydeepseek"
        minSdk = 26
        targetSdk = 37
        // 版本号取本仓库（我们的功能线），上游的 testInstrumentationRunner 保留
        /*
          * 版本从 1.0.0 起算：换包名等于换了一条发行线（com.zhunemo.mydeepseek），
          * 之前 com.simplechat.app 的 16 / 1.2.3 属于另一条线，不必延续。
          */
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "com.simplechat.app.DeviceRegression"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 「关于」页直接读 BuildConfig.VERSION_NAME，避免手写版本号跟 build.gradle 漂移
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material.kolor)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
    implementation(libs.highlights)
    implementation(libs.latex.renderer)
    implementation(libs.latex.renderer.fonts)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
