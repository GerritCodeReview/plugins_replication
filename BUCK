gerrit_plugin(
  name = 'replication',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/resources/**/*']),
  manifest_entries = [
    'Implementation-Title: Replication plugin',
    'Implementation-URL: http://code.google.com/p/replication/',
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
    '//lib:junit',
    '//lib/jgit:jgit',
  ],
)
