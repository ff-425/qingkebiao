plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.qingkebiao.timetable"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qingkebiao.timetable"
        // minSdk 26 是为了直接用 java.time，不必上 desugaring。
        // 小米近几年的机型都远高于这个。
        minSdk = 26
        targetSdk = 34
        // 每次发版都要涨。涨了系统才会拦住"装回旧版"——
        // 旧版读不懂新数据文件是唯一能把用户手动加的课和调休记录搞没的路径。
        // versionName 就用大版本号，和发给用户的 qingkebiao-vN.apk 对得上。
        versionCode = 10
        versionName = "10"
    }

    // 固定的 debug 签名。默认行为是用 ~/.android/debug.keystore，而 CI runner
    // 每次都是全新环境、每次生成的 key 都不同 —— 结果是两次构建出来的包互相装不上，
    // 每次更新都要先卸载、数据全丢。把 keystore 放进仓库，本地和 CI 就永远一致。
    // 这是 debug 签名，口令是众所周知的 android，不用于任何发布用途。
    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // 个人自用，直接拿 debug 签名，省掉 keystore 这一套
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // 桌面小组件
    implementation("androidx.glance:glance-appwidget:1.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
