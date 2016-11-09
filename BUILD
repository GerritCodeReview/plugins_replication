load('//tools/bzl:junit.bzl', 'junit_tests')
load('//tools/bzl:plugin.bzl', 'gerrit_plugin')

gerrit_plugin(
  name = 'replication',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/resources/**/*']),
  manifest_entries = [
    'Implementation-Title: Replication plugin',
    'Implementation-URL: https://gerrit-review.googlesource.com/#/admin/projects/plugins/replication',
    'Gerrit-PluginName: replication',
    'Gerrit-Module: com.googlesource.gerrit.plugins.replication.ReplicationModule',
    'Gerrit-SshModule: com.googlesource.gerrit.plugins.replication.SshModule'
  ],
  deps = [
    '//lib:commons-io',
  ],
)

junit_tests(
  name = 'replication_tests',
  srcs = glob(['src/test/java/**/*Test.java']),
  tags = ['replication'],
  deps = [
    ':replication_util',
    ':replication__plugin',
    '//gerrit-acceptance-framework:lib',
    '//gerrit-plugin-api:lib',
  ],
  visibility = ['//visibility:public'],
  testonly = 1,
)

java_library(
  name = 'replication_util',
  srcs = glob(['src/test/java/**/*.java'],
              exclude = ['src/test/java/**/*Test.java']),
  deps = [
    ':replication__plugin',
    '//gerrit-acceptance-framework:lib',
    '//gerrit-plugin-api:lib',
  ],
  testonly = 1,
)
