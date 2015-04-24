@PLUGIN@ list
==============

NAME
----
@PLUGIN@ list - List specific remote destinations information

SYNOPSIS
--------
```
ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list
  [--remote <PATTERN>]
  [--detail]
  [--json]
```

DESCRIPTION
-----------
List all remote destinations information, or only those whose
name match the pattern given on the command line.

ACCESS
------
Caller must be a member of the privileged 'Administrators' group.

SCRIPTING
---------
This command is intended to be used in scripts. It is very useful
for replication status check for administrators as well.

OPTIONS
-------

`--remote <PATTERN>`
:	Only print destinations whose remote name contains
	the substring `PATTERN`.

`--detail`
:	Print remote detail information: Name, Url, AdminUrl,
	AuthGroup and Project.

`--json`
:	Output in json format.

EXAMPLES
--------
List all destinations:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list
```

List all destinations detail information:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list --detail
```

List all destinations detail information in json format:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list --detail --json
```

List destinations whose name contains mirror:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list --remote mirror
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list --remote ^.*mirror.*
```

SEE ALSO
--------

* [Replication Configuration](config.html)
* [Access Control](../../../Documentation/access-control.html)
