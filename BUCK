gerrit_plugin(
  name = 'replication',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/resources/**/*']),
  manifest_entries = [
    'Gerrit-PluginName: replication',
    'Gerrit-Module: com.googlesource.gerrit.plugins.replication.ReplicationModule',
    'Gerrit-SshModule: com.googlesource.gerrit.plugins.replication.SshModule'
  ],
  deps = [
    '//lib/commons:io',
  ],
)

java_test(
  name = 'replication_tests',
  srcs = glob(['src/test/java/**/*.java']),
  deps = [
    ':replication__plugin__compile',
    '//gerrit-common:server',
    '//gerrit-reviewdb:server',
    '//gerrit-server:server',
    '//lib:easymock',
    '//lib:gwtorm',
    '//lib:junit',
    '//lib/jgit:jgit',
  ],
)
