import java.util.Properties

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
        versionCode = 28
        versionName = "28"

        // OCR 带进来的原生库每个 ABI 都是十几兆，而一台手机只用得上一份。
        // 默认只打 arm64-v8a（2017 年以后的安卓机几乎全是），
        // 模拟器测试用 -PqkbAbi=x86_64。差别是 57 MB 跟 25 MB。
        ndk {
            abiFilters += (project.findProperty("qkbAbi") as String? ?: "arm64-v8a").split(",")
        }
    }

    // 发布签名。
    //
    // 以前这里用的是仓库里那个 debug.keystore，口令就是众所周知的 "android" ——
    // 意味着任何人都能签一个同名包直接覆盖安装到用户手机上，系统不会有任何提示。
    // 现在换成真正的 release key：文件和口令都在 keystore.properties 里，
    // 那个文件不进仓库。
    //
    // 拿不到 keystore.properties（比如 CI、或者别人 clone 了仓库）就退回 debug 签名，
    // 这样代码照样能编过 —— 但那样出来的包装不到用户手机上，也不该拿去分发。
    signingConfigs {
        create("release") {
            val props = Properties()
            val f = rootProject.file("keystore.properties")
            if (f.exists()) {
                f.inputStream().use { props.load(it) }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // -PqkbSign=debug 强制用旧的 debug 签名。
    // 用途只有一个：换签名那次，老用户手机上是旧签名的包，装不了新签名的版本，
    // 而旧版本又没有"导出备份"这个功能 —— 等于让人在没有备份的情况下卸载。
    // 所以要发一个"带备份功能 + 旧签名"的过渡包，让人能先把数据导出来。
    val forceDebugSign = (project.findProperty("qkbSign") as String?) == "debug"
    val hasReleaseKey = rootProject.file("keystore.properties").exists() && !forceDebugSign

    buildTypes {
        debug {
            // 带上 OCR 模型之后包有 25.3 MiB，而 Cloudflare Pages 单文件上限
            // 正好是 25 MiB —— 差 300 KB 传不上去。开 R8 顺手把 dex 砍一刀，
            // 用户那边下载也少十几兆。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 我们只发这一个构建类型，所以 debug 也用 release key 签
            signingConfig =
                if (hasReleaseKey) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig =
                if (hasReleaseKey) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
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
    // 顶栏、设置列表用的那几个基础图标。material3 本来就间接带着它，写明是免得哪天升级被拿掉
    implementation("androidx.compose.material:material-icons-core")

    // 桌面小组件
    implementation("androidx.glance:glance-appwidget:1.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 图片/PDF 课表识别。用 bundled 版（模型打包进 APK）而不是
    // 依赖 Google Play 服务的那个版本 —— 国内很多手机根本没装 GMS，
    // 那个版本在用户手上就是直接不能用。代价是 APK 大一圈。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 纯逻辑的单测（解析器、周次计算、差异比对）。
    // 跑在 JVM 上，不需要设备，./gradlew test 几秒出结果。
    testImplementation("junit:junit:4.13.2")
}
