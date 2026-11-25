plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.myfamily.locshare"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.myfamily.locshare"
        minSdk = 26
        targetSdk = 34
        // GitHub Actions 실행 번호 = 릴리스 태그(build-N) = versionCode. 앱 내 업데이트가 이 숫자로 새 버전을 판단한다.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.2"
    }

    // 고정 서명키: GitHub Actions에서 빌드할 때마다 키가 바뀌어
    // "업데이트 설치 실패"가 나는 것을 막기 위해 저장소에 포함합니다. (가족용 사이드로드 전용)
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
            isMinifyEnabled = false
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 위치 (Fused Location Provider)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // 서버: Firebase (로그인 + Realtime Database)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")

    // 지도: 카카오맵 SDK v2 (네이티브 앱 키 필요)
    implementation("com.kakao.maps.open:android:2.15.2")
}
