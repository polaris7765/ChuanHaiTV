plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yujiannatian.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yujiannatian.tv"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val storeFileProp = project.findProperty("RELEASE_STORE_FILE") as String?
            val storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
            val keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
            val keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
            // 缺失签名配置时跳过，避免 CI / 无密钥环境下 release 任务直接报错
            if (storeFileProp != null && storePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = file(storeFileProp)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // media3 的 @UnstableApi 属注解约定，不是运行时兼容问题；
        // 降级为提示以免在每个调用链上层层标注，NewApi 等 API 兼容性检查仍为 Error。
        warning.add("UnsafeOptInUsageError")
    }

    compileOptions {
        // Jsoup 1.17.x 使用 java.util.function / java.util.stream，
        // 这些类在 Android 7.0 (API 24) 之前不存在，必须做核心库降级才能在 API 23 电视上运行。
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // HLS (m3u8) 播放
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // 网络 / HTML 解析 / 本地缓存
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 片名拼音首字母（另有内置 GB2312 首字母表兜底）
    implementation("com.belerweb:pinyin4j:2.5.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
