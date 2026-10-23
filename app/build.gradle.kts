plugins {
    id("com.android.application")
}

android {
    namespace = "jp.ef64.privlink"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.ef64.privlink"
        minSdk = 29   // RoleManager（デフォルトブラウザ申請）と DayNight テーマが API 29 以降のため
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // CI（GitHub Actions）は毎回まっさらな環境でビルドするため、標準のままだと署名の鍵が
    // ビルドごとに変わり、更新インストールが「競合」で失敗する。固定の鍵で署名して防ぐ。
    // （個人用のサイドロード APK 向けの debug 鍵。ストア配布には使わないこと）
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // 配布用に署名する場合は signingConfigs を追加する。個人利用なら debug ビルドで十分。
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 依存ライブラリなし（AndroidX も不使用）。ビルド環境を最小にするため。
dependencies {
}
