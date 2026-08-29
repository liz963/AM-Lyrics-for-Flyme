plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.amlyric.flyme"
    // Android 16（Baklava）
    compileSdk = 36

    defaultConfig {
        applicationId = "com.amlyric.flyme"
        // libxposed API 要求 minSdk >= 26；Flyme + Android 16 远高于此
        minSdk = 26
        targetSdk = 36
        // 仓库里能下到的最新稳定 build-tools 是 35.0.1（无 35.0.0）
        buildToolsVersion = "35.0.1"
        versionCode = 11
        versionName = "1.3.6"
    }

    buildTypes {
        release {
            // 模块本身没有代码量，混淆只会带来反射风险
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // libxposed API 102 的字节码基线是 Java 17
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 状态栏歌词模块体量极小，release 的 vital lint 检查只会拖慢构建且无收益
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        // Gradle 守护进程建议用 JDK 21（Android Studio 内置 JBR 21 即可），
        // 但产物字节码保持 17，与 libxposed API 的基线一致
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // libxposed 现代 API（API 102），仅编译期使用，运行时由 LSPosed 框架提供
    compileOnly("io.github.libxposed:api:102.0.0")
}
