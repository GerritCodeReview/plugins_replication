load("@rules_java//java:defs.bzl", "java_library")
load("//tools/bzl:junit.bzl", "junit_tests")
load("//tools/bzl:plugin.bzl", "PLUGIN_DEPS", "PLUGIN_TEST_DEPS", "gerrit_plugin")

gerrit_plugin(
    name = "replication",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Implementation-Title: Replication plugin",
        "Implementation-URL: https://gerrit-review.googlesource.com/#/admin/projects/plugins/replication",
        "Gerrit-PluginName: replication",
        "Gerrit-InitStep: com.googlesource.gerrit.plugins.replication.Init",
        "Gerrit-Module: com.googlesource.gerrit.plugins.replication.ReplicationModule",
        "Gerrit-SshModule: com.googlesource.gerrit.plugins.replication.SshModule",
        "Gerrit-HttpModule: com.googlesource.gerrit.plugins.replication.HttpModule",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        "//lib/commons:io",
    ],
)

junit_tests(
    name = "replication_tests",
    srcs = glob([
        "src/test/java/**/*Test.java",
        "src/test/java/**/*IT.java",
    ]),
    tags = ["replication"],
    visibility = ["//visibility:public"],
    deps = PLUGIN_TEST_DEPS + PLUGIN_DEPS + [
        ":replication__plugin",
        ":replication_util",
    ],
)

java_library(
    name = "replication_util",
    testonly = True,
    srcs = glob(
        ["src/test/java/**/*.java"],
        exclude = ["src/test/java/**/*Test.java"],
    ),
    deps = PLUGIN_TEST_DEPS + PLUGIN_DEPS + [
        ":replication__plugin",
    ],
)
