@PLUGIN@ Cross Plugin Communication
===================================

The replication plugin exposes _ApiModule_ that allows to provide _Cross Plugin
Communication_.

Setup
-----

Check the [official documentation](https://gerrit-review.googlesource.com/Documentation/dev-plugins.html#_cross_plugin_communication)
on how to setup your project.

Working with [Extension Points](./extension-point.md)
-----------------------------------------------------

In order to use both, the _Cross Plugin Communication_ and replication
_Extension Points_, follow the [Install extension libModule](./extension-point.md#install-extension-libmodule)
steps and make sure that `replication.jar` is only present in `lib/` directory.

Exposed API
-----------

* `com.googlesource.gerrit.plugins.replication.ReplicationConfigOverrides`

  Override current replication configuration from external source (eg. git
  repository, ZooKeeper).

  Replication plugin will still use configuration from `$gerrit_site/etc/`, but
  with overrides it can be modified dynamically from external source, similarly to
  how `git config` uses _user_ and _repository_ configuration files.

  Only one override at a time is supported. The implementation needs to bind a
  `DynamicItem`.

  ```java
  DynamicItem.bind(binder(), ReplicationConfigOverrides.class).to(ReplicationConfigOverridesImpl.class);
  ```
