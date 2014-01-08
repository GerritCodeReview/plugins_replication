@PLUGIN@ remotes
================

NAME
----
@PLUGIN@ remotes - Output the list of replication remotes

SYNOPSIS
--------
```
ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ remotes
  [--url <PATTERN>]
```

DESCRIPTION
-----------
Outputs a list of replication remotes and their urls.

ACCESS
------
Caller must be a member of the privileged 'Administrators' group,
or have been granted the 'Start Replication' plugin-owned capability.

SCRIPTING
---------
This command is intended to be used in scripts.

OPTIONS
-------

`--url <PATTERN>`
:	Output only the remotes whose URL contains the substring `PATTERN`.
	This can be useful to predicate which remotes match a url before
	specifying a url to the start command.

EXAMPLES
--------
```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ remotes
  remote: local
     url: file:///my/local/replication/path/${name}.git

```

SEE ALSO
--------

* [Replication Configuration](config.md)
* [Access Control](../../../Documentation/access-control.html)
