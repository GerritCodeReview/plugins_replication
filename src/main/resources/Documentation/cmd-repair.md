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
  [--full | [--copy-loose-objects] [--copy-packs]]
  <PROJECT>
```

DESCRIPTION
-----------
Repairs a project on its replication destinations, then runs a
`@PLUGIN@ start` for that project (with `--now --wait`) so
any refs that diverged during the repair are replicated. The command
blocks until replication finishes.

If no repair action flag is supplied, `--full` is assumed.

Repair actions always run in a fixed order, regardless of the order the
flags are given on the command line: loose objects are copied before packs,
so that objects a pack may need are already present on the destination when
the pack becomes visible there.

For each remote, [remote.NAME.adminUrl](config.md#remote.NAME.adminUrl) is
preferred when set (same as repository creation); otherwise
[remote.NAME.url](config.md#remote.NAME.url) is used. Only plain SSH
destinations are eligible (for example `user@host:/path/to/repo.git`).
Destinations whose URL uses `gerrit+ssh`, HTTP(S), or a local path are
skipped.

REQUIREMENTS
------------
The Gerrit runtime user must have `ssh` on `PATH`, plus `rsync` either on
`PATH` or pointed at via [`replication.rsyncPath`](config.md#replication.rsyncPath).

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
replication destinations whose configuration URL contains the substring
`PATTERN`, or whose expanded project URL contains `PATTERN`.

`--full`
: Run every supported repair action.

`--copy-loose-objects`
: rsync the loose object files held in the two hex character fanout
directories under `objects/` to each matching destination. Everything else
under `objects/`, such as `pack/` and `info/`, is left untouched.

`--copy-packs`
: rsync regular files in `objects/pack/` whose names end with `.pack`,
`.idx`, `.bitmap`, or `.rev` to each matching destination. The `.pack`
files are copied before the files indexing them.

`PROJECT`
: Exact Gerrit project name.

EXAMPLES
--------
Run every supported repair action for `tools/gerrit` against every
eligible destination, then replicate refs (`--full` is implied since no
action flag is given):

```console
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ repair tools/gerrit
```

Equivalent, with `--full` stated explicitly:

```console
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ repair --full tools/gerrit
```

Only copy packs (no other repair actions, even if more are added later):

```console
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ repair --copy-packs tools/gerrit
```

Only copy loose objects:

```console
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ repair --copy-loose-objects tools/gerrit
```

Copy loose objects and packs, but no other repair action that may be added
later (loose objects are copied first even though `--copy-packs` is given
first):

```console
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ repair --copy-packs --copy-loose-objects tools/gerrit
```

Repair only against destinations whose URL mentions `replica1`:

```console
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ repair --url replica1 tools/gerrit
```

SEE ALSO
--------

* [@PLUGIN@ start](cmd-start.md)
* [Replication Configuration](config.md)
* [Access Control](../../../Documentation/access-control.html)
