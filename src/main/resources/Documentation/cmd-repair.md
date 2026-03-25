@PLUGIN@ repair
===============

NAME
----
@PLUGIN@ repair - Repair a project on replication destinations

SYNOPSIS
--------

```console
ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ repair
  [--url <PATTERN>]
  --copy-packs
  <PROJECT>
```

DESCRIPTION
-----------
Repairs a project on its replication destinations, then runs a
`@PLUGIN@ start` for that project (with `--now --wait`) so
any refs that diverged during the repair are replicated. The command
blocks until replication finishes.

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
Exit status is non-zero if the project is missing, or if the repair fails
for some reason.

OPTIONS
-------

`--url <PATTERN>`
: Restrict both the repair action(s) and the follow-up replication to
destinations whose configured URL, or expanded project URL, contains the
substring `PATTERN`.

`--copy-packs`
: rsync regular files in `objects/pack/` whose names end with `.pack`,
`.idx`, `.bitmap`, or `.rev` to each matching destination. Only plain
SSH URLs are used (for example `user@host:/path/to/repo.git`).
Destinations that use `gerrit+ssh`, HTTP(S), or local paths are skipped.

`PROJECT`
: Exact Gerrit project name.

EXAMPLES
--------
Copy packs for `tools/gerrit` to every eligible SSH destination, then
replicate refs:

```console
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ repair --copy-packs tools/gerrit
```

Repair only against destinations whose URL mentions `replica1`:

```console
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ repair --url replica1 --copy-packs tools/gerrit
```

SEE ALSO
--------

* [@PLUGIN@ start](cmd-start.md)
* [Replication Configuration](config.md)
* [Access Control](../../../Documentation/access-control.html)
