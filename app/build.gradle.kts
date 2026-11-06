plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hsh.islandtimer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hsh.islandtimer"
        minSdk = 26
        targetSdk = 34
        versionCode = 10000
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 正式签名配置占位：正式岛靠「指纹证书 + APP_ID」鉴权，
            // 上线前必须固定同一签名证书（见 HANDOFF §7）。
            // 1) keytool -genkeypair -v -keystore release.keystore -alias islandtimer \
            //        -keyalg RSA -keysize 2048 -validity 10000
            // 2) 项目根目录建 keystore.properties：
            //        storeFile=release.keystore
            //        storePassword=***
            //        keyAlias=islandtimer
            //        keyPassword=***
            // 3) 取消下方注释后同步即可：
            // import java.util.Properties
            // val keystoreProps = Properties().apply {
            //     val f = rootProject.file("keystore.properties")
            //     if (f.exists()) f.inputStream().use { load(it) }
            // }
            // signingConfigs {
            //     create("release") {
            //         if (keystoreProps.containsKey("storeFile")) {
            //             storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            //             storePassword = keystoreProps.getProperty("storePassword")
            //             keyAlias = keystoreProps.getProperty("keyAlias")
            //             keyPassword = keystoreProps.getProperty("keyPassword")
            //         }
            //     }
            // }
            // signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // UsageStats 账本周期对账
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 富样式超级岛（可选，拿到小米官方 APP_ID 后启用）：
    // implementation("io.github.d4viddf:hyperisland_kit:0.4.5")
}
