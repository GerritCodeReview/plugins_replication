@PLUGIN@ abort
==============

NAME
----
@PLUGIN@ abort - Abort replication to a remote destination.

SYNOPSIS
--------
```
ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ abort
  [--remote <PATTERN>]
  [--dry-run]
```

DESCRIPTION
-----------
Aborts all replications to remote destinations.

ACCESS
------
Caller must be a member of the privileged 'Administrators' group.

SCRIPTING
---------
This command is intended to be used in scripts.

OPTIONS
-------

`--remote <PATTERN>`
:	Required. Abort replication to destinations whose remote name matches
	the `PATTERN`.

`--dry-run`
:	Dry run mode. Only print the names of the destinations that will be aborted.

EXAMPLES
--------

Abort replication to destination whose name contains mirror:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ abort --remote mirror
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ abort --remote ^.*mirror.*
```

SEE ALSO
--------

* [Replication Configuration](config.html)
* [Access Control](../../../Documentation/access-control.html)
