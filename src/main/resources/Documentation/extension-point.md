@PLUGIN@ extension points
==============

The replication plugin exposes an extension point for influencing the behaviour of the replication events from another plugin or a script.
Extension points can be defined from the replication plugin only when it is loaded as [libModule](/config-gerrit.html#gerrit.installModule) and
implemented by another plugin by declaring a `provided` dependency from the replication plugin.

### Install extension libModule

The replication plugin's extension points are defined in the `com.googlesource.gerrit.plugins.replication.ReplicationExtensionPointModule`
that needs to be configured as libModule. Create a symbolic link from `$GERRIT_SITE/plugins/replication.jar` into `$GERRIT_SITE/lib` and then add
the replication extension module to the `gerrit.config` as shown in the example below:

```
installModule = com.googlesource.gerrit.plugins.replication.ReplicationExtensionPointModule
```

> NOTE: Use and configuration of the replication plugin as library module requires a Gerrit server restart and does not support hot plugin install or upgrade.


### Extension points

* `com.googlesource.gerrit.plugins.replication.ReplicationPushFilter`

  Filter out the ref updates pushed to a remote instance.
  Only one filter at a time is supported. Filter implementation needs to bind a `DynamicItem`.

  Default: no filtering

  Example:

  ```
  DynamicItem.bind(binder(),ReplicationPushFilter.class).to(<ReplicationPushFilterImplementation>.class);
  ```
