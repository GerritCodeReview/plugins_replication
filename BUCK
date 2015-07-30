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
    '//lib/commons:io',
  ],
  provided_deps = [
    '//lib:gson',
    '//lib/log:log4j'
  ],
)

java_test(
  name = 'replication_tests',
  srcs = glob(['src/test/java/**/*.java']),
  labels = ['replication'],
  source_under_test = [':replication__plugin'],
  deps = [
    ':replication__plugin',
    '//gerrit-common:server',
    '//gerrit-reviewdb:server',
    '//gerrit-server:server',
    '//lib:gwtorm',
    '//lib:junit',
    '//lib/easymock:easymock',
    '//lib/jgit:jgit',
  ],
)
