pluginManagement {
    repositories {
        // 阿里云镜像（国内优先，避免 dl.google.com / repo.maven.apache.org 被墙）
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        // 官方源兜底
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        // io.github.libxposed:api 在 Maven Central（阿里云 central 镜像同步即可）
        google()
        mavenCentral()
    }
}

rootProject.name = "AMFlymeLyric"
include(":app")
