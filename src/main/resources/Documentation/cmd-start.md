@PLUGIN@ start
==============

NAME
----
@PLUGIN@ start - Manually trigger replication, to recover a node

SYNOPSIS
--------
```
ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ start
  [--wait]
  [--url <PATTERN>]
  {--all | <PROJECT> ...}
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

ACCESS
------
Caller must be a member of the privileged 'Administrators' group,
or have been granted [the 'Start Replication' global capability][1].

[1]: ../../../Documentation/access-control.html#capability_startReplication

SCRIPTING
---------
This command is intended to be used in scripts.

OPTIONS
-------

`--wait`
:	Wait for replication to finish before exiting.

`--all`
:	Schedule replication for all projects.

`--url <PATTERN>`
:	Replicate only to replication destinations whose URL contains
	the substring <PATTERN>.  This can be useful to replicate
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

SEE ALSO
--------

* [Replication Configuration](config.html)
* [Access Control](../../../Documentation/access-control.html)
