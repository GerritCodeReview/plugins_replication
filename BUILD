load("//tools/bzl:junit.bzl", "junit_tests")
load("//tools/bzl:plugin.bzl", "gerrit_plugin", "PLUGIN_DEPS", "PLUGIN_TEST_DEPS")

gerrit_plugin(
    name = "replication",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Implementation-Title: Replication plugin",
        "Implementation-URL: https://gerrit-review.googlesource.com/#/admin/projects/plugins/replication",
        "Gerrit-PluginName: replication",
        "Gerrit-Module: com.googlesource.gerrit.plugins.replication.ReplicationModule",
        "Gerrit-SshModule: com.googlesource.gerrit.plugins.replication.SshModule",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        "//lib:commons-io",
    ],
)

junit_tests(
    name = "replication_tests",
    srcs = glob(["src/test/java/**/*Test.java"]),
    tags = ["replication"],
    visibility = ["//visibility:public"],
    deps = PLUGIN_TEST_DEPS + PLUGIN_DEPS + [
        ":replication__plugin",
        ":replication_util",
    ],
)

java_library(
    name = "replication_util",
    testonly = 1,
    srcs = glob(
        ["src/test/java/**/*.java"],
        exclude = ["src/test/java/**/*Test.java"],
    ),
    deps = PLUGIN_TEST_DEPS + PLUGIN_DEPS + [
        ":replication__plugin",
    ],
)
