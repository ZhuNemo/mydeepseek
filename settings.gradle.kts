rootProject.name = "Simple Chat"

/*
 * ⚠️ 这里**只能放官方仓库**，不要提交任何国内镜像。
 *
 * CI 跑在 GitHub 的境外 runner 上：阿里云镜像对境外 IP 不稳定（时快时 404），
 * 而 Gradle 一旦在某个仓库上拿到"找不到"的结论就会继续往下问，
 * 排在后面的官方仓库反而被拖慢甚至被漏掉。
 *
 * 国内网络需要加速时**不要改这个文件**，用用户级 init 脚本
 * （`~/.gradle/init.gradle.kts`）：不进仓库、不影响 CI。
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

include(":app")
