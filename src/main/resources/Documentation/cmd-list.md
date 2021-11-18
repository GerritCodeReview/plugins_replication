@PLUGIN@ list
==============

NAME
----
@PLUGIN@ list - List remote destination information.

SYNOPSIS
--------
```console
ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list
  [--remote <PATTERN>]
  [--detail]
  [--json]
```

DESCRIPTION
-----------
Lists the name and URL for remote destinations.

ACCESS
------
Caller must be a member of the privileged 'Administrators' group.

SCRIPTING
---------
This command is intended to be used in scripts.

OPTIONS
-------

`--remote <PATTERN>`
:	Only print information for destinations whose remote name matches
	the `PATTERN`.

`--detail`
:	Print additional detailed information: AdminUrl, AuthGroup, Project
	and queue (pending and in-flight).

`--json`
:	Output in json format.

EXAMPLES
--------
List all destinations:

```console
$ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list
```

List all destinations detail information:

```console
$ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list --detail
```

List all destinations detail information in json format:

```console
$ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list --detail --json
```

List destinations whose name contains mirror:

```console
$ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list --remote mirror
$ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list --remote ^.*mirror.*
```

SEE ALSO
--------

* [Replication Configuration](config.md)
* [Access Control](../../../Documentation/access-control.html)
