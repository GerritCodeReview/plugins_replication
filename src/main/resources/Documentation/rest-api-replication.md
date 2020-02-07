@PLUGIN@ - /replication/ REST API
===================================

This page describes the REST endpoint that is added by the @PLUGIN@
plugin.

Please also take note of the general information on the
[REST API](../../../Documentation/rest-api.html).

<a id="server-config-endpoints">Server Config Endpoints
------------------------------------------

### <a id="get-content">Start Replication for Project
POST /plugins/replication/start

This is a REST equivalent to the Ssh start replication command. For more information, refer to:
* [Ssh replication start command](cmd-start.md)

There are two supported variants of the command, all and by project

### <a id="get-content">Start Replication for Project
POST /plugins/replication/start
#### Request
```
POST /plugins/replication/start HTTP/1.0

  {
    "command" : "project",
    "project" : "repo1",
  }

```

#### Response
```
HTTP/1.1 204 NO_CONTENT
```

### <a id="get-content">Start Replication for All
POST /plugins/replication/start
#### Request
```
POST /plugins/replication/start HTTP/1.0

  {
    "command" : "all",
  }
```

#### Response
```
HTTP/1.1 204 NO_CONTENT
```


OPTIONS:

Options applicable to both variants above
-------
"now" : true

Start replicating right away without waiting the per remote replication delay.

"wait" : true

Wait for replication to finish before exiting.

"url" : "\<PATTERN\>"

Replicate only to replication destinations whose URL contains the substring `PATTERN`.
This can be useful to replicate only to a previously down node, which has been brought back
online.


EXAMPLES
--------
Replicate every project, to every configured remote:

#### Request
```
POST /plugins/replication/start HTTP/1.0

  {
    "command" : "all"
  }
```

#### Response
```
HTTP/1.1 204 NO_CONTENT
```


Replicate all to 'srv2' now that it's back online and start immediately

#### Request
```
POST /plugins/replication/start HTTP/1.0

  {
    "command" : "all",
    "url" : "srv2",
    "now" : true
  }
```

#### Response
```
HTTP/1.1 204 NO_CONTENT
```

Replicate only projects located in the `documentation` subdirectory, start immediately
and wait for completion:

#### Request
```
POST /plugins/replication/start HTTP/1.0

  {
    "command" : "project",
    "project" : "documentation/*",
    "now" : true,
    "wait" : true
  }
```

#### Response
```
HTTP/1.1 204 NO_CONTENT
```