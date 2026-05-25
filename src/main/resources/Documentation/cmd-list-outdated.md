@PLUGIN@ list-outdated
======================

NAME
----
@PLUGIN@ list-outdated - List outdated replication state.

SYNOPSIS
--------

```console
ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list-outdated
  [--project <PROJECT>]
  [--remote <REMOTE>]
  [--by-ref]
```

DESCRIPTION
-----------
Reports projects and refs that are out of sync between this server and
its configured replication destinations.

ACCESS
------
Caller must be a member of the privileged 'Administrators' group.

SCRIPTING
---------
This command is intended to be used in scripts. Output is tab-separated
so it can be parsed with `awk`, `cut`, etc.

OPTIONS
-------

`--project <PROJECT>`
: Project to check. May be repeated. If omitted, all projects in the
project cache are checked.

`--remote <REMOTE>`
: Remote (`remote.<name>` section in `replication.config`) to check.
May be repeated. If omitted, all configured remotes are checked.

`--by-ref`
: Report each outdated ref on its own row, including local and remote
SHAs. Without this flag, a destination URI with any outdated ref for
a project produces one project-level row.

OUTPUT
------
Without `--by-ref`, one row per outdated `(remote, URI, project)`:

```
<remote>\t<uri>\t<project>
```

With `--by-ref`, one row per outdated ref:

```
<remote>\t<uri>\t<project>\t<localRef>\t<remoteRef>\t<localSha>\t<remoteSha>
```

`<remoteRef>` reflects any rename performed by the destination's push
refspec (e.g., a destination configured with
`refs/heads/*:refs/replicated/*` reports the renamed destination name).

A missing remote ref is reported with the all-zero SHA
`0000000000000000000000000000000000000000`.

Errors are written to stderr:

```
error: <project>: <message>
```

EXAMPLES
--------
Check every project against every configured remote, project-level
output only:

```console
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list-outdated
```

Check a single project against a single remote, listing each outdated
ref:

```console
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list-outdated \
      --project my/project --remote mirror1 --by-ref
```

Check several projects against several remotes:

```console
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list-outdated \
      --project foo --project bar \
      --remote mirror1 --remote mirror2
```

SEE ALSO
--------

* [Replication Configuration](config.md)
* [Access Control](../../../Documentation/access-control.html)
