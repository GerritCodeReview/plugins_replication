@PLUGIN@ start
==============

NAME
----
@PLUGIN@ start - Manually trigger replication, to recover a node

SYNOPSIS
--------
```
ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ start
  [--now]
  [--wait]
  [--url <PATTERN>]
  {--all | <PROJECT PATTERN> ...}
```

DESCRIPTION
-----------
Schedules replication of the specified projects to all configured
replication destinations, or only those whose URLs match the pattern
given on the command line.

Normally Gerrit automatically schedules replication whenever it
makes a change to a managed Git repository.  However, there are
other reasons why an administrator may wish to trigger replication:

* Destination disappears, then later comes back online.

	If a destination went offline for a period of time, when it
	comes back, it may be missing commits that it should have.
	Triggering a replication run for all projects against that URL
	will update it.

* After repacking locally, and using `rsync` to distribute the new
  pack files to the destinations.

	If the local server is repacked, and then the resulting pack
	files are sent to remote peers using `rsync -a
	--delete-after`, there is a chance that the rsync missed a
	change that was added during the rsync data transfer, and the
	rsync will remove that changes's data from the remote, even
	though the automatic replication pushed it there in parallel
	to the rsync.

	It's a good idea to run replicate with `--all` to ensure all
	projects are consistent after the rsync is complete.

* After deleting a ref by hand.

	If a ref must be removed (e.g. to purge a change or patch set
	that shouldn't have been created, and that must be eradicated)
	that delete must be done by direct git access on the local,
	managed repository.  Gerrit won't know about the delete, and
	is unable to replicate it automatically.  Triggering
	replication on just the affected project can update the
	mirrors.

If you get message "Nothing to replicate" while running this command,
it may be caused by several reasons, such as you give a wrong url
pattern in command options, or the authGroup in the replication.config
has no read access for the replicated projects.

If one or several project patterns are supplied, only those projects
conforming to both this/these pattern(s) and those defined in
replication.config for the target host(s) are queued for replication.

The patterns follow the same format as those in replication.config,
where wildcard or regular expression patterns can be given.
Regular expression patterns must match a complete project name to be
considered a match.

A regular expression pattern starts with `^` and a wildcard pattern ends
with a `*`. If the pattern starts with `^` and ends with `*`, it is
treated as a regular expression.

ACCESS
------
Caller must be a member of the privileged 'Administrators' group,
or have been granted the 'Start Replication' plugin-owned capability.

SCRIPTING
---------
This command is intended to be used in scripts.

OPTIONS
-------

`--now`
:   Start replicating right away without waiting the per remote
	replication delay.

`--wait`
:	Wait for replication to finish before exiting.

`--all`
:	Schedule replication for all projects.

`--url <PATTERN>`
:	Replicate only to replication destinations whose URL contains
	the substring `PATTERN`.  This can be useful to replicate
	only to a previously down node, which has been brought back
	online.

EXAMPLES
--------
Replicate every project, to every configured remote:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ start --all
```

Replicate only to `srv2` now that it is back online:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ start --url srv2 --all
```

Replicate only the `tools/gerrit` project, after deleting a ref
locally by hand:

```
  $ git --git-dir=/home/git/tools/gerrit.git update-ref -d refs/changes/00/100/1
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ start tools/gerrit
```

Replicate only projects located in the `documentation` subdirectory:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ start documentation/*
```

Replicate projects whose path includes a folder named `vendor` to host slave1:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ start --url slave1 ^(|.*/)vendor(|/.*)
```

SEE ALSO
--------

* [Replication Configuration](config.md)
* [Access Control](../../../Documentation/access-control.html)
