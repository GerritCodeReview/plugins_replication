gerrit_plugin(
  name = 'replication',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/resources/**/*']),
)

java_test(
  name = 'replication_tests',
  srcs = glob(['src/test/java/**/*.java']),
  deps = [
    ':replication__plugin__compile',
    '//lib:junit',
  ],
)
