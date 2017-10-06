This plugin can automatically push any changes Gerrit Code Review
makes to its managed Git repositories to another system.  Usually this
would be configured to provide mirroring of changes, for warm-standby
backups, or a load-balanced public mirror farm.

The replication runs on a short delay.  This gives the server a small
time window to batch updates going to the same project, such as when a
user uploads multiple changes at once.

Typically replication should be done over SSH, with a passwordless
public/private key pair.  On a trusted network it is also possible to
use replication over the insecure (but much faster due to no
authentication overhead or encryption) git:// protocol, by enabling
the `receive-pack` service on the receiving system, but this
configuration is not recommended.  It is also possible to specify a
local path as replication target. This makes e.g. sense if a network
share is mounted to which the repositories should be replicated.

Replication of account data (NoteDb)
------------------------------------

To replicate the account data in NoteDb the following branches from the
`All-Users` repository must be replicated:

* `refs/users/*` (user branches)
* `refs/meta/external-ids` (external IDs)
* `refs/starred-changes/*` (star labels)
* `refs/sequences/accounts` (account sequence numbers, not needed for Gerrit
  slaves)

