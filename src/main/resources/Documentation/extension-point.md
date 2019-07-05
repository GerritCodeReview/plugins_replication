@PLUGIN@ extension points
==============

### Setup
Extension points can be exposed only from library module. To setup extension points install @PLUGIN@.jar as a library module in the `$GERRIT_SITE/lib` directory and add following line to the `gerrit.config` file ([documentation](https://gerrit-review.googlesource.com/Documentation/config-gerrit.html#gerrit.installModule)):

```
installModule = com.googlesource.gerrit.plugins.replication.ReplicationExtensionPointModule
```
The limitation of library module is that it cannot be hot reloaded. This means that changes for extension points defined in `ReplicationExtensionPointModule` are visible only after Gerrit server restart. 


### Extension points:
* `com.googlesource.gerrit.plugins.replication.ReplicationPushFilter` - Filter that is invoked before list of remote ref updates is pushed to remote instance. It can be used to filter out unwanted updates. Filter is defined as a `DynamicItem` this means that it can be bind in other plugin: `DynamicItem.bind(binder(),ReplicationPushFilter.class).to(<ReplicationPushFilterImplementation>.class);`
