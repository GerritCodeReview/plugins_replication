Replication Configuration
=========================

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

Enabling Replication
--------------------

If replicating over SSH (recommended), ensure the host key of the
remote system(s) is already in the Gerrit user's `~/.ssh/known_hosts`
file.  The easiest way to add the host key is to connect once by hand
with the command line:

```
  sudo su -c 'ssh mirror1.us.some.org echo' gerrit2
```

<a name="example_file">
Next, create `$site_path/etc/replication.config` as a Git-style config
file, for example to replicate in parallel to four different hosts:</a>

```
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

```
  ssh -p 29418 localhost gerrit plugin reload replication
```

To manually trigger replication at runtime, see
SSH command [start](cmd-start.html).

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
	are in-sync with this server.  By default, true.

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
	out where each project needs to be replicated.

	See [git push][1] for details on Git URL syntax.

[1]: http://www.git-scm.com/docs/git-push#URLS

remote.NAME.adminUrl
:	Address of the alternative remote server only for repository
	creation.  Multiple URLs may be specified within a single
	remote block, listing different destinations which share the
	same settings.

	The adminUrl can be used as an ssh alternative to the url
	option, but only related to repository creation.  If not
	specified, the repository creation tries to follow the default
	way through the url value specified.

	It is useful when the remote.NAME.url protocols do not allow
	repository creation although their usage is mandatory in the
	local environment.  In that case, an alternative SSH url could
	be specified to repository creation.

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

	Multiple push keys can be supplied, to specify multiple
	patterns to match against.  In the [example above][2], remote
	"pubmirror" uses two push keys to match both `refs/heads/*`
	and `refs/tags/*`, but excludes all others, including
	`refs/changes/*`.

	Defaults to `+refs/*:refs/*` (all refs) if not specified.

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
:	Number of seconds to wait before scheduling a remote push
	operation.  Setting the delay to 0 effectively disables the
	delay, causing the push to start as soon as possible.

	This is a Gerrit specific extension to the Git remote block.

	By default, 15 seconds.

remote.NAME.replicationRetry
:	Number of minutes to wait before scheduling a remote push
	operation previously failed due to an offline remote server.

	If a remote push operation fails because a remote server was
	offline, all push operations to the same destination URL are
	blocked, and the remote push is continuously retried.

	This is a Gerrit specific extension to the Git remote block.

	By default, 1 minute.

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
	access the repositories. Multiple authGroups may be specified
	within a single remote block to signify a wider access right.
	In the project administration web interface the read access
	can be specified for this group to control if a project should
	be replicated or not to the remote.

	By default, replicates without group control, i.e. replicates
	everything to all remotes.

remote.NAME.replicatePermissions
:	If true, permissions-only projects and the refs/meta/config
	branch will also be replicated to the remote site.  These
	projects and branches may be needed to keep a backup or slave
	server current.

	By default, true, replicating everything.

remote.NAME.mirror
:	If true, replication will remove remote branches that absent
	locally or invisible to the replication (for example read
	access denied via `authGroup` option).

	By default, false, do not remove remote branches.

remote.NAME.remoteNameStyle
:	Slashes in the `${name}` placeholder are replaced with either
	dashes or underscores.

	Github and Gitorious do not permit slashes "/" in repository
	names and changes this to dashes "-" at repository creation
	time. If set to "dash," this changes slashes to dashes in the
	repository name. If set to "underscore", this changes slashes
	to underscores in the repository name.

	By default, "slash," remote name will contain slashes as they
	do in Gerrit.

remote.NAME.projects
:	Specifies which repositories should be replicated to the
	remote. It can be provided more than once, and supports three
	formats: regular expressions, wildcard matching, and single
	project matching. All three formats match case-sensitive.

	Values starting with a caret `^` are treated as regular
	expressions. `^foo/(bar|baz)` would match the projects
	`foo/bar`, and `foo/baz`. Regular expressions have to fully
	match the project name. So the above example would not match
	`foo/bar2`, while `^foo/(bar|baz).*` would.

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

If present, Gerrit reads and caches `~/.ssh/config` at startup, and
supports most SSH configuration options.  For example:

```
  Host host-one.example.com:
    IdentityFile ~/.ssh/id_hostone
    PreferredAuthentications publickey

  Host mirror*.us.some.org:
    User mirror-updater
    IdentityFile ~/.ssh/id_pubmirror
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
