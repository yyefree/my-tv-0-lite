pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
    }
}
dependencyResolutionManagement {
    // 本机 GRADLE_USER_HOME 里有全局 init.gradle 会注入仓库，
    // FAIL_ON_PROJECT_REPOS 会因此直接报错，这里放宽为优先用工程内声明
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
    }
}

rootProject.name = "My TV 0"
include(":app")
 