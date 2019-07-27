load("@rules_java//java:defs.bzl", "java_library")
load("//tools/bzl:junit.bzl", "junit_tests")
load("//tools/bzl:plugin.bzl", "gerrit_plugin")

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
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        "//lib/commons:io",
    ],
)

junit_tests(
    name = "replication_tests",
    srcs = glob(["src/test/java/**/*Test.java"]),
    tags = ["replication"],
    visibility = ["//visibility:public"],
    deps = [
        ":replication__plugin",
        ":replication_util",
        "//gerrit-acceptance-framework:lib",
        "//gerrit-plugin-api:lib",
    ],
)

java_library(
    name = "replication_util",
    testonly = 1,
    srcs = glob(
        ["src/test/java/**/*.java"],
        exclude = ["src/test/java/**/*Test.java"],
    ),
    deps = [
        ":replication__plugin",
        "//gerrit-acceptance-framework:lib",
        "//gerrit-plugin-api:lib",
    ],
)
