pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "LoopIME"
include(":app")
check(file("app/libs/sherpa-onnx-1.13.8.aar").isFile) {
    "请先运行 python3 scripts/materialize-dependencies.py，校验并还原仓库内分块依赖。"
}
