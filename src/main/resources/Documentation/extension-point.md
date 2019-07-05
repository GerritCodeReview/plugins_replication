@PLUGIN@ extension points
==============

To setup extension points install @PLUGIN@.jar as a library module in the `$GERRIT_SITE/lib` directory and add following line to the `gerrit.config` file ([documentation](https://gerrit-review.googlesource.com/Documentation/config-gerrit.html#gerrit.installModule)):

```
installModule = com.googlesource.gerrit.plugins.replication.ReplicationExtensionPointModule
```

### Extension points:
* `com.googlesource.gerrit.plugins.replication.ReplicationPushFilter` - Filter that is invoked before list of remote ref updates is pushed to remote instance. It can be used to filter out unwanted updates.
