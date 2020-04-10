@PLUGIN@ list
==============

NAME
----
@PLUGIN@ convert - Extract remote sections from replication.config to separate files
remote configuration files.

SYNOPSIS
--------
```
ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ convert
  [--cleanup]
  [--output <DIRECTORY PATH>]
```

DESCRIPTION
-----------
Replication plugin is capable to load both replication.config file and all *.config
files from etc/replication directory. This command allows to extract remote configurations
from replication.config to separate remote configuration files.

ACCESS
------
Caller must be a member of the privileged 'Administrators' group.

SCRIPTING
---------
This command is intended to be used in scripts.

OPTIONS
-------

`--cleanup`
:	Remove extracted remote sections from replication.config file.

    By default set to false.

`--output`
:	Directory where remote configuration files will be stored.

	By default set to $GERRIT_SITE/etc/replication.

EXAMPLES
--------
Extract remote sections to $GERRIT_SITE/etc/replication. Keep original replication.config:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ convert
```

Extract remote sections to $GERRIT_SITE/etc/replication and remove from replication.config:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ convert --cleanup
```

Extract remote sections to specified directory. Keep original replication.config:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list convert --output /tmp/replication
```

SEE ALSO
--------

* [Replication Configuration](config.md)
* [Access Control](../../../Documentation/access-control.html)
