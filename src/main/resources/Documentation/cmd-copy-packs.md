@PLUGIN@ copy-packs
===================

NAME
----
@PLUGIN@ copy-packs - Copy pack files to replication destinations for one project

SYNOPSIS
--------

```console
ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ copy-packs
  [--url <PATTERN>]
  <PROJECT>
```

DESCRIPTION
-----------
Copies regular files in `objects/pack/` whose names end with `.pack`,
`.idx`, `.bitmap`, or `.rev`. It does not update refs; use
[@PLUGIN@ start](cmd-start.md) to replicate ref updates as usual. Only
plain SSH URLs are used (for example `user@host:/path/to/repo.git`).
Destinations that use `gerrit+ssh`, HTTP(S), or local paths are skipped.

REQUIREMENTS
------------
The Gerrit runtime user must have `rsync` and `ssh` on `PATH`.

ACCESS
------
Caller must be a member of the privileged 'Administrators' group,
or have been granted the 'Start Replication' plugin-owned capability.

SCRIPTING
---------
This command is intended to be used to repair repositories on the mirror.
Exit status is non-zero if the project is missing, `objects/pack` is absent,
or if the copy fails.

OPTIONS
-------

`--url <PATTERN>`
: Copy only to replication destinations whose configured URL, or expanded
project URL, contains the substring `PATTERN`.

`PROJECT`
: Exact Gerrit project name.

EXAMPLES
--------
Copy packs for project `tools/gerrit` to every eligible SSH destination:

```console
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ copy-packs tools/gerrit
```

Copy packs only to destinations whose URL mentions `replica1`:

```console
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ copy-packs --url replica1 tools/gerrit
```

SEE ALSO
--------

* [@PLUGIN@ start](cmd-start.md)
* [Replication Configuration](config.md)
* [Access Control](../../../Documentation/access-control.html)
