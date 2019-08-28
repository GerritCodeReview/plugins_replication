@PLUGIN@ extension points
==============

The replication plugin exposes an extension point to allow influencing its behaviour from another plugin or a script.
Extension points can be defined from the replication plugin only when it is loaded as [libModule](/config-gerrit.html#gerrit.installModule) and
implemented by another plugin by declaring a `provided` dependency from the replication plugin.

### Install extension libModule

The replication plugin's extension points are defined in the `c.g.g.p.r.ReplicationExtensionPointModule`
that needs to be configured as libModule.

Create a symbolic link from `$GERRIT_SITE/plugins/replication.jar` into `$GERRIT_SITE/lib`
and then add the replication extension module to the `gerrit.config`.

Example:

```
[gerrit]
  installModule = com.googlesource.gerrit.plugins.replication.ReplicationExtensionPointModule
```

> **NOTE**: Use and configuration of the replication plugin as library module requires a Gerrit server restart and does not support hot plugin install or upgrade.


### Extension points

* `com.googlesource.gerrit.plugins.replication.ReplicationPushFilter`

  Filter out the ref updates pushed to a remote instance.
  Only one filter at a time is supported. Filter implementation needs to bind a `DynamicItem`.

  Default: no filtering

  Example:

  ```
  DynamicItem.bind(binder(), ReplicationPushFilter.class).to(ReplicationPushFilterImpl.class);
  ```

* `com.googlesource.gerrit.plugins.replication.AdminApiFactory`

  Create an instance of `AdminApi` for a given remote URL. The default implementation
  provides API instances for local FS, remote SSH, and remote Gerrit.

  Only one factory at a time is supported. The implementation needs to be bound as a
  `DynamicItem`.

  Example:

  ```
  DynamicItem.bind(binder(), AdminApiFactory.class).to(AdminApiFactoryImpl.class);
  ```
