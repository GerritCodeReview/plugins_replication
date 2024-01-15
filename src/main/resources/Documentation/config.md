Replication Configuration
=========================

Enabling Replication
--------------------

If replicating over SSH, ensure the host key of the
remote system(s) is already in the Gerrit user's `~/.ssh/known_hosts`
file.  The easiest way to add the host key is to connect once by hand
with the command line:

```console
  sudo su -c 'ssh mirror1.us.some.org echo' gerrit2
```

*NOTE:* make sure the local user's ssh keys format is PEM, here how to generate them:
```console
  ssh-keygen -m PEM -t rsa -C "your_email@example.com"
```

<a name="example_file"></a>
Next, create `$site_path/etc/replication.config` as a Git-style config
file, for example to replicate in parallel to four different hosts:

```ini
  [remote "host-one"]
    url = gerrit2@host-one.example.com:/some/path/${name}.git

  [remote "pubmirror"]
    url = mirror1.us.some.org:/pub/git/${name}.git
    url = mirror2.us.some.org:/pub/git/${name}.git
    url = mirror3.us.some.org:/pub/git/${name}.git
    push = +refs/heads/*:refs/heads/*
    push = +refs/tags/*:refs/tags/*
    threads = 3
    authGroup = Public Mirror Group
    authGroup = Second Public Mirror Group
```

Then reload the replication plugin to pick up the new configuration:

```console
  ssh -p 29418 localhost gerrit plugin reload replication
```

To manually trigger replication at runtime, see
SSH command [start](cmd-start.md).

<a name="configuring-cluster-replication"></a>
Configuring Cluster Replication
-------------------------------

The replication plugin is designed to allow multiple primaries in a
cluster to efficiently cooperate together via the replication event
persistence subsystem. To enable this cooperation, the directory
pointed to by the `replication.eventsDirectory` config key must reside on
a shared filesystem, such as NFS. By default, simply pointing multiple
primaries to the same eventsDirectory will enable some cooperation by
preventing the same replication push from being duplicated by more
than one primary.

To further improve cooperation across the cluster, the
`replication.distributionInterval` config value can be set. With
distribution enabled, the replication queues for all the nodes sharing
the same `eventsDirectory` will reflect approximately the same outstanding
replication work (i.e. tasks waiting in the queue). Replication pushes
which are running will continue to only be visible in the queue of the
node on which the push is actually happening. This feature helps
administrators get a cluster wide view of outstanding replication
tasks, while allowing replication tasks triggered by one primary to be
fulfilled by another node which is less busy.

This enhanced replication work distribution allows the amount of
replication work a cluster can handle to scale more evenly and linearly
with the amount of primaries in the cluster. Adding more nodes to a
cluster without distribution enabled will generally not allow the thread
count per remote to be reduced without impacting service levels to those
remotes. This is because without distribution, all events triggered by a
node will only be fulfilled by the node which triggered the event, even
if all the other nodes in the cluster are idle. This behavior implies
that each node should be configured in a way that allows it alone to
provide the level of service which each remote requires. However, with
distribution enabled, it becomes possible to reduce the amount of
replication threads configured per remote proportionally to the amount
of nodes in the cluster, while maintaining the same approximate service
level as before adding new nodes.

Threads per remote reduction without service impacts is possible with
distribution, because when configuring a node it can be expected that
other nodes will pick up some of the work it triggers. Then the node no
longer needs to be configured as if it were the only node in the
cluster. For example, if a remote requires 6 threads with one node to
achieve acceptable service, it should only take 2 threads on 3
equivalently powered nodes to provide the same service level with
distribution enabled. Scaling down such thread requirements per remote
results in a reduced memory footprint per remote on each node in the
cluster. This enables the nodes in the cluster to now scale to handle
more remotes with the approximate same service level than without
distribution. The amount of extra supported remotes then also scales
approximately linearly with the extra nodes in a cluster.

File `replication.config`
-------------------------

The optional file `$site_path/etc/replication.config` is a Git-style
config file that controls the replication settings for the replication
plugin.

The file is composed of one or more `remote` sections, each remote
section provides common configuration settings for one or more
destination URLs.

Each remote section uses its own thread pool.  If pushing to
multiple remotes, over differing types of network connections
(e.g. LAN and also public Internet), its a good idea to put them
into different remote sections, so that replication to the slower
connection does not starve out the faster local one.  The example
file above does this.

In the keys below, the `NAME` portion is unused by this plugin, but
must be unique to distinguish the different sections if more than one
remote section appears in the file.

gerrit.replicateOnStartup
:	If true, replicates to all remotes on startup to ensure they
	are in-sync with this server.  By default, false.

gerrit.autoReload
:	If true, automatically reloads replication destinations and settings
	after `replication.config` file is updated, without the need to restart
	the replication plugin. When the reload takes place, pending replication
	events based on old settings are discarded. By default, false.

gerrit.defaultForceUpdate
:	If true, the default push refspec will be set to use forced
	update to the remote when no refspec is given.  By default, false.

gerrit.maxRefsToLog
:	Number of refs, that are pushed during replication, to be logged.
	For printing all refs to the logs, use a value of 0. By default, 0.

gerrit.maxRefsToShow
:	Number of refs, that are pushed during replication, to be shown
	in the show-queue output. To show all refs, use a value of 0.
	By default, 2, because whenever a new patchset is created there
	are two refs (change ref and meta ref) eg.

	`(retry 1) push aaa.com:/git/test.git [refs/heads/b1 refs/heads/b2 (+2)]`


gerrit.pushBatchSize
:	Max number of refs that are pushed in a single push operation. If more
	than pushBatchSize are to be pushed then they are divided into batches
	and pushed sequentially one-by-one.

	Can be overridden at remote-level by setting pushBatchSize.

	By default, `0`, which means that there are no limitations on number of
	refs to be transferred in a single push operation. Note that negative
	values are treated as `0`.

	Note that `pushBatchSize` is ignored when *Cluster Replication* is configured
	- when `replication.distributionInterval` has value > 0.

gerrit.sshCommandTimeout
:	Timeout for SSH command execution. If 0, there is no timeout and
	the client waits indefinitely. By default, 0.

gerrit.sshConnectionTimeout
:	Timeout for SSH connections. If 0, there is no timeout and
        the client waits indefinitely. By default, 2 minutes.

replication.distributionInterval
:	Interval in seconds for running the replication distributor. When
	run, the replication distributor will add all persisted waiting tasks
	to the queue to ensure that externally loaded tasks are visible to
	the current process. If zero, turn off the replication distributor. By
	default, zero.

	Turning this on is likely only useful when there are other processes
	(such as other masters in the same cluster) writing to the same
	persistence store. To ensure that updates are seen well before their
	replicationDelay expires when the distributor is used, the recommended
	value for this is approximately the smallest `remote.NAME.replicationDelay`
	divided by 5.

<a name="replication.updateRefErrorMaxRetries">replication.updateRefErrorMaxRetries</a>
:	Number of times to retry a replication operation if an update
	ref error is detected.

	If two or more replication operations (to the same GIT and Ref)
	are scheduled at approximately the same time (and end up on different
	replication threads), there is a large probability that the last
	push to complete will fail with a remote "failed to update ref" error.
	This error may also occur due to a transient issue like file system
	being full which was previously returned as "failed to write" by git.

	This option allows Gerrit to retry the replication push when the
	"failed to update ref" error is detected. Also retry when the error
	"failed to lock" is detected as that is the legacy string used by git.

	A good value would be 3 retries or less, depending on how often
	you see `updateRefError` collisions in your server logs. A too highly set
	value risks keeping around the replication operations in the queue
	for a long time, and the number of items in the queue will increase
	with time.

	Normally Gerrit will succeed with the replication during its first
	retry, but in certain edge cases (e.g. a mirror introduces a ref
	namespace with the same name as a branch on the master) the retry
	will never succeed.

	The issue can also be mitigated somewhat by increasing the
	`replicationDelay`.

	Default: 0 (disabled, i.e. never retry)

replication.lockErrorMaxRetries
:	Refer to the [replication.updateRefErrorMaxRetries][4] section.

	If both `lockErrorMaxRetries` and `updateRefErrorMaxRetries` are
	configured, then `updateRefErrorMaxRetries` takes precedence.

	Default: 0 (disabled, i.e. never retry)

[4]: #replication.updateRefErrorMaxRetries

replication.maxRetries
:	Maximum number of times to retry a push operation that previously
	failed.

	When a push operation reaches its maximum number of retries,
	the replication event is discarded from the queue and the remote
	destinations may remain out of sync.

	Can be overridden at remote-level by setting `replicationMaxRetries`.

	By default, pushes are retried indefinitely.

replication.eventsDirectory
: Directory where replication events are persisted

	When scheduling a replication, the replication event is persisted
	under this directory. When the replication is done, the event is deleted.
	If plugin is stopped before all scheduled replications are done, the
	persisted events will not be deleted. When the plugin is started again,
	it will trigger all replications found under this directory.

	For replication to work, is is important that atomic renames be possible
	from within any subdirectory of the `eventsDirectory` to within any other
	subdirectory of the `eventsDirectory`. This generally means that the entire
	contents of the `eventsDirectory` should live on the same filesystem.

	When not set, defaults to the plugin's data directory.

remote.NAME.url
:	Address of the remote server to push to.  Multiple URLs may be
	specified within a single remote block, listing different
	destinations which share the same settings.  Assuming
	sufficient threads in the thread pool, Gerrit pushes to all
	URLs in parallel, using one thread per URL.

	Within each URL value the magic placeholder `${name}` is
	replaced with the Gerrit project name.  This is a Gerrit
	specific extension to the otherwise standard Git URL syntax
	and it must be included in each URL so that Gerrit can figure
	out where each project needs to be replicated. `${name}` may
	only be omitted if the remote refers to a single repository
	(i.e.: Exactly one [remote.NAME.projects][3] and that name's
	value is a single project match.).

	See [git push][1] for details on Git URL syntax.

[1]: http://www.git-scm.com/docs/git-push#URLS
[3]: #remote.NAME.projects

remote.NAME.adminUrl
:	Address of the alternative remote server only for repository
	creation.  Multiple URLs may be specified within a single
	remote block, listing different destinations which share the
	same settings.

	The `adminUrl` can be used as an ssh alternative to the `url`
	option, but only related to repository creation.  If not
	specified, the repository creation tries to follow the default
	way through the url value specified.

	It is useful when the `remote.NAME.url` protocols do not allow
	repository creation although their usage is mandatory in the
	local environment.  In that case, an alternative SSH url could
	be specified to repository creation.

	To enable replication to different Gerrit instance use
	`gerrit+http://` or `gerrit+https://` as protocol name followed
	by hostname of another Gerrit server eg.

	`gerrit+http://replica2.my.org/`
	<br>
	`gerrit+https://replica3.my.org/`

	In this case replication will use Gerrit's REST API
	to create/remove projects and update repository HEAD references.

	NOTE: In order to replicate project deletion, the
	[delete-project](https://gerrit-review.googlesource.com/admin/projects/plugins/delete-project)
	plugin must be installed on the other Gerrit.

	*Backward compatibility notice*

	Before Gerrit v2.13 it was possible to enable replication to different
	Gerrit masters using `gerrit+ssh://`
	as protocol name followed by hostname of another Gerrit server eg.

	`gerrit+ssh://replica1.my.org/`

	In that case replication would have used Gerrit's SSH API to
	create/remove projects and update repository HEAD references.

	The `gerrit+ssh` option is kept for backward compatibility, however
	the use-case behind it is not valid anymore since the introduction of
	Lucene indexes and the removal of ReviewDb, which would require
	a lot more machinery to setup a master to master replication scenario.

	The `gerrit+ssh` option is still possible but is limited to the
	ability to replicate only regular Git repositories that do not
	contain any code-review or NoteDb information.

	Using `gerrit+ssh` for replicating all Gerrit repositories
	would result in failures on the `All-Users.git` replication and
	would not be able to replicate changes magic refs and indexes
	across nodes.

remote.NAME.receivepack
:	Path of the `git-receive-pack` executable on the remote
	system, if using the SSH transport.

	Defaults to `git-receive-pack`.

remote.NAME.uploadpack
:	Path of the `git-upload-pack` executable on the remote system,
	if using the SSH transport.

	Defaults to `git-upload-pack`.

remote.NAME.push
:	Standard Git refspec denoting what should be replicated.
	Setting this to `+refs/heads/*:refs/heads/*` would mirror only
	the active branches, but not the change refs under
	`refs/changes/`, or the tags under `refs/tags/`.

	Note that prefixing a source refspec with `+` causes the replication
	to be done with a `git push --force` command.
	Be aware that when you are pushing to remote repositories that may
	have read/write access (e.g. GitHub) you may want to omit the `+`
	to prevent the risk of overwriting branches that have been modified
	on the remote.

	Multiple push keys can be supplied, to specify multiple
	patterns to match against.  In the [example above][2], remote
	"pubmirror" uses two push keys to match both `refs/heads/*`
	and `refs/tags/*`, but excludes all others, including
	`refs/changes/*`.

	Defaults to `refs/*:refs/*` (push all refs) if not specified,
	or `+refs/*:refs/*` (force push all refs) if not specified and
	`gerrit.defaultForceUpdate` is true.

	Note that the `refs/meta/config` branch is only replicated
	when `replicatePermissions` is true, even if the push refspec
	is 'all refs'.

[2]: #example_file

remote.NAME.timeout
:	Number of seconds to wait for a network read or write to
	complete before giving up and declaring the remote side is not
	responding.  If 0, there is no timeout, and the push client
	waits indefinitely.

	A timeout should be large enough to mostly transfer the
	objects to the other side.  1 second may be too small for
	larger projects, especially over a WAN link, while 10-30
	seconds is a much more reasonable timeout value.

	Defaults to 0 seconds, wait indefinitely.

remote.NAME.replicationDelay
:	Time to wait before scheduling a remote push operation. Setting
	the delay to 0 effectively disables the delay, causing the push
	to start as soon as possible.

	This is a Gerrit specific extension to the Git remote block.

	By default, 15 seconds.

remote.NAME.rescheduleDelay
:	Delay when rescheduling a push operation due to an in-flight push
	running for the same project.

	Cannot be set to a value lower than 3 seconds to avoid a tight loop
	of schedule/run which could cause 1K+ retries per second.

	A configured value lower than 3 seconds will be rounded to 3 seconds.

	By default, 3 seconds.

remote.NAME.replicationRetry
:	Time to wait before scheduling a remote push operation previously
	failed due to an offline remote server.

	If a remote push operation fails because a remote server was
	offline, all push operations to the same destination URL are
	blocked, and the remote push is continuously retried unless
	the replicationMaxRetries value is set.

	This is a Gerrit specific extension to the Git remote block.

	By default, 1 minute.

remote.NAME.replicationMaxRetries
:	Maximum number of times to retry a push operation that previously
	failed.

	When a push operation reaches its maximum number of retries
	the replication event is discarded from the queue and the remote
	destinations could be out of sync.

	This is a Gerrit specific extension to the Git remote block.

	By default, use `replication.maxRetries`.

remote.NAME.drainQueueAttempts
:	Maximum number of attempts to drain the replication event queue before
	stopping the plugin.

	When stopping the plugin, the shutdown will be delayed trying to drain
	the event queue.

	The maximum delay is `drainQueueAttempts * replicationDelay` seconds.

	When not set or set to 0, the queue is not drained and the pending
	replication events are cancelled.

	By default, do not drain replication events.

remote.NAME.threads
:	Number of worker threads to dedicate to pushing to the
	repositories described by this remote.  Each thread can push
	one project at a time, to one destination URL.  Scheduling
	within the thread pool is done on a per-project basis.  If a
	remote block describes 4 URLs, allocating 4 threads in the
	pool will permit some level of parallel pushing.

	By default, 1 thread.

remote.NAME.authGroup
:	Specifies the name of a group that the remote should use to
	access the repositories. Multiple `authGroups` may be specified
	within a single remote block to signify a wider access right.
	In the project administration web interface the read access
	can be specified for this group to control if a project should
	be replicated or not to the remote.

	By default, replicates without group control, i.e. replicates
	everything to all remotes.

	*NOTE:* If an authGroup is provided, and you want a complete
	mirror (for backup reasons or to run a Gerrit replica), at
	least one of the provided authGroups must have "Access Database"
	capability. Otherwise [db](../../../Documentation/note-db.html)
	refs will not be replicated.

remote.NAME.createMissingRepositories
:	If true, a repository is automatically created on the remote site.
	If the remote site was not available at the moment when a new
	project was created, it will be created if during the replication
	of a ref it is found to be missing.

	If false, repositories are never created automatically on this
	remote.

	By default, true, missing repositories are created.

remote.NAME.replicatePermissions
:	If true, permissions-only projects and the refs/meta/config
	branch will also be replicated to the remote site.  These
	projects and branches may be needed to keep a backup or slave
	server current.

	By default, true, replicating everything.

remote.NAME.replicateProjectDeletions
:	If true, project deletions will also be replicated to the
	remote site.

	By default, false, do *not* replicate project deletions.

remote.NAME.replicateHiddenProjects
:	If true, hidden projects will be replicated to the remote site.

	By default, false, do *not* replicate hidden projects.

remote.NAME.mirror
:	If true, replication will remove remote branches that are absent
	locally or invisible to the replication (for example read
	access denied via `authGroup` option).

	By default, false, do not remove remote branches.

remote.NAME.remoteNameStyle
:	Provides possibilities to influence the name of the target
	repository, e.g. by replacing slashes in the `${name}`
	placeholder, when the target remote repository is not served
	by Gerrit.

	Github and Gitorious do not permit slashes "/" in repository
	names and will change them to dashes "-" at repository creation
	time.

	If this setting is set to "dash", slashes will be replaced with
	dashes in the remote repository name. If set to "underscore",
	slashes will be replaced with underscores in the repository name.

	Option `basenameOnly` makes `${name}` to be only the basename
	(the part after the last slash) of the repository path on the
	Gerrit server, e.g. `${name}` of `foo/bar/my-repo.git` would
	be `my-repo`.

	> **NOTE**: The use of repository name translation using `remoteNameStyle`
	> may lead to dangerous situations if there are multiple repositories
	> that may be mapped to the same target name. For instance when
	> mapping `/foo/my-repo.git` to `my-repo` using "basenameOnly"
	> would also map `/bar/my-repo.git` to the same `my-repo` leading
	> to conflicts where commits can be lost between the two repositories
	> replicating to the same target `my-repo`.

	By default, `slash`, i.e. remote names will contain slashes as
	they do in Gerrit.

<a name="remote.NAME.projects">remote.NAME.projects</a>
:	Specifies which repositories should be replicated to the
	remote. It can be provided more than once, and supports three
	formats: regular expressions, wildcard matching, and single
	project matching. All three formats match case-sensitive.

	Values starting with a caret `^` are treated as regular
	expressions. `^foo/(bar|baz)` would match the projects
	`foo/bar`, and `foo/baz`. Regular expressions have to fully
	match the project name. So the above example would not match
	`foo/bar2`, while `^foo/(bar|baz).*` would.

	Projects may be excluded from replication by using a regular
	expression with inverse match. `^(?:(?!PATTERN).)*$` will
	exclude any project that matches.

	Values that are not regular expressions and end in `*` are
	treated as wildcard matches. Wildcards match projects whose
	name agrees from the beginning until the trailing `*`. So
	`foo/b*` would match the projects `foo/b`, `foo/bar`, and
	`foo/baz`, but neither `foobar`, nor `bar/foo/baz`.

	Values that are neither regular expressions nor wildcards are
	treated as single project matches. So `foo/bar` matches only
	the project `foo/bar`, but no other project.

	By default, replicates without matching, i.e. replicates
	everything to all remotes.

<a name="remote.NAME.slowLatencyThreshold">remote.NAME.slowLatencyThreshold</a>
:	the time duration after which the replication of a project to this
	destination will be considered "slow". A slow project replication
	will cause additional metrics to be exposed for further investigation.
	See [metrics.md](metrics.md) for further details.

	default: 15 minutes

remote.NAME.pushBatchSize
:	Max number of refs that are pushed in a single push operation to this
	destination. If more than `pushBatchSize` are to be pushed then they are
	divided into batches and pushed sequentially one-by-one.

	By default it falls back to `gerrit.pushBatchSize` value (which is `0` if
	not set, which means that there are no limitations on number of refs to
	be transferred in a single push operation). Note that negative values are
	treated as `0`.

	Note that `pushBatchSize` is ignored when *Cluster Replication* is configured
	- when `replication.distributionInterval` has value > 0.

remote.NAME.replicateNoteDbMetaRefs
: Whether to replicate the NoteDb meta refs (`refs/changes/*/meta`,
  `refs/changes/*/robot-comments`, `refs/draft-comments/*`,
  `refs/starred-changes/*`) or not. This setting is useful when the remote
  replica does not run a Gerrit instance and one wants to turn off replicating
  NoteDb meta refs to that remote.

  By default, true.


Directory `replication`
--------------------
The optional directory `$site_path/etc/replication` contains Git-style
config files that controls the replication settings for the replication
plugin. When present all `remote` sections from `replication.config` file are
ignored.

Files are composed of one `remote` section. Multiple `remote` sections or any
other section makes the file invalid and skipped by the replication plugin.
File name defines remote section name. Each section provides common configuration
settings for one or more destination URLs. For more details how to setup `remote`
sections please refer to the `replication.config` section.

### Configuration example:

Static configuration in `$site_path/etc/replication.config`:

```ini
[gerrit]
    autoReload = true
    replicateOnStartup = false
[replication]
    lockErrorMaxRetries = 5
    maxRetries = 5
```

Remote sections in `$site_path/etc/replication` directory:

* File `$site_path/etc/replication/host-one.config`

 ```ini
 [remote]
    url = gerrit2@host-one.example.com:/some/path/${name}.git
 ```


* File `$site_path/etc/replication/pubmirror.config`

 ```ini
  [remote]
    url = mirror1.us.some.org:/pub/git/${name}.git
    url = mirror2.us.some.org:/pub/git/${name}.git
    url = mirror3.us.some.org:/pub/git/${name}.git
    push = +refs/heads/*:refs/heads/*
    push = +refs/tags/*:refs/tags/*
    threads = 3
    authGroup = Public Mirror Group
    authGroup = Second Public Mirror Group
 ```

Replication plugin resolves config files to the following configuration:

```ini
[gerrit]
    autoReload = true
    replicateOnStartup = false
[replication]
    lockErrorMaxRetries = 5
    maxRetries = 5

[remote "host-one"]
    url = gerrit2@host-one.example.com:/some/path/${name}.git

[remote "pubmirror"]
    url = mirror1.us.some.org:/pub/git/${name}.git
    url = mirror2.us.some.org:/pub/git/${name}.git
    url = mirror3.us.some.org:/pub/git/${name}.git
    push = +refs/heads/*:refs/heads/*
    push = +refs/tags/*:refs/tags/*
    threads = 3
    authGroup = Public Mirror Group
    authGroup = Second Public Mirror Group
```

File `secure.config`
--------------------

The optional file `$site_path/secure.config` is a Git-style config
file that provides secure values that should not be world-readable,
such as passwords. Passwords for HTTP remotes can be obtained from
this file.

remote.NAME.username
:	Username to use for HTTP authentication on this remote, if not
	given in the URL.

remote.NAME.password
:	Password to use for HTTP authentication on this remote.

File `~/.ssh/config`
--------------------

Gerrit reads and caches the `~/.ssh/config` at startup, and
supports most SSH configuration options.  For example:

```text
  Host host-one.example.com
    IdentityFile ~/.ssh/id_hostone
    PreferredAuthentications publickey

  Host mirror*.us.some.org
    User mirror-updater
    IdentityFile ~/.ssh/id_pubmirror
    PreferredAuthentications publickey
```

`IdentityFile` and `PreferredAuthentications` must be defined for all the hosts.
Here an example of the minimum `~/.ssh/config` needed:

```text
  Host *
    IdentityFile ~/.ssh/id_rsa
    PreferredAuthentications publickey
```

Supported options:

  * Host
  * Hostname
  * User
  * Port
  * IdentityFile
  * PreferredAuthentications
  * StrictHostKeyChecking

SSH authentication must be by passwordless public key, as there is no
facility to read passphrases on startup or passwords during the SSH
connection setup, and SSH agents are not supported from Java.

Host keys for any destination SSH servers must appear in the user's
`~/.ssh/known_hosts` file, and must be added in advance, before Gerrit
starts.  If a host key is not listed, Gerrit will be unable to connect
to that destination, and replication to that URL will fail.
