load("@rules_java//java:defs.bzl", "java_library")
load("//:version.bzl", "GERRIT_VERSION")
load("//tools/bzl:junit.bzl", "junit_tests")
load("//tools/bzl:plugin.bzl", "PLUGIN_DEPS", "PLUGIN_TEST_DEPS", "gerrit_plugin")

MAVEN_REPOSITORY = "sonatype-nexus-staging"

URL = "https://oss.sonatype.org/content/repositories/snapshots" if GERRIT_VERSION.endswith("-SNAPSHOT") else "https://oss.sonatype.org/service/local/staging/deploy/maven2"

gerrit_plugin(
    name = "replication",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Implementation-Title: Replication plugin",
        "Implementation-URL: https://gerrit-review.googlesource.com/#/admin/projects/plugins/replication",
        "Gerrit-PluginName: replication",
        "Gerrit-ApiModule: com.googlesource.gerrit.plugins.replication.ApiModule",
        "Gerrit-InitStep: com.googlesource.gerrit.plugins.replication.Init",
        "Gerrit-Module: com.googlesource.gerrit.plugins.replication.ReplicationModule",
        "Gerrit-SshModule: com.googlesource.gerrit.plugins.replication.SshModule",
    ],
    resources = glob(["src/main/resources/**/*"]),
    pom_file = "replication-plugin_pom.xml",
    repository = MAVEN_REPOSITORY,
    url = URL,
    version = GERRIT_VERSION,
)

junit_tests(
    name = "replication_tests",
    timeout = "long",
    srcs = glob([
        "src/test/java/**/*Test.java",
    ]),
    tags = ["replication"],
    visibility = ["//visibility:public"],
    deps = PLUGIN_TEST_DEPS + PLUGIN_DEPS + [
        ":replication__plugin",
        ":replication_util",
    ],
)

[junit_tests(
    name = f[:f.index(".")].replace("/", "_"),
    srcs = [f],
    tags = ["replication"],
    visibility = ["//visibility:public"],
    deps = PLUGIN_TEST_DEPS + PLUGIN_DEPS + [
        ":replication__plugin",
        ":replication_util",
    ],
) for f in glob(["src/test/java/**/*IT.java"])]

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
