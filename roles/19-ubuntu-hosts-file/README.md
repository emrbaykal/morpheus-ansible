# Role 19: ubuntu-hosts-file

## Overview

Writes every node of the Morpheus instance (IP and hostname) into `/etc/hosts`, so the cluster keeps
working when DNS does not.

## What it does

1. Builds the node list from `morpheus['instance']['containers']` (`hostname` + `internalIp`), sorted
   by hostname. Fails without touching the file if any node of the instance is missing one of the two.
2. Writes them between `# BEGIN/END MORPHEUS INSTANCE NODES (managed by Ansible)` markers. If the
   node's `server.fqdn` is known it is written first, as the canonical name, with the short name as an
   alias.
3. Removes this node's own names from the `127.0.0.1` line that provisioning writes, so the hostname
   resolves to the real address instead of loopback.
4. Removes the `127.0.1.1` line inherited from the template image.

Steps 3 and 4 are skipped in controller-only mode (see below).

## Variables

| Variable | Default | Description |
|---|---|---|
| `hosts_file_path` | `/etc/hosts` | Only a variable so the role can be tested against a copy. |
| `hosts_file_controller_only` | `false` | `true`: do not touch this node; write the block on the controller instead, through `delegate_to` with the controller's IP. |

## Where it runs

| Playbook | Mode | Why |
|---|---|---|
| `ubuntu-k8-post-provision.yml` | normal, first role | Provision phase; `instance.containers` already holds every node with its IP (field-verified 2026-09-17). |
| `ubuntu-k8-join-node.yml` | `hosts_file_controller_only: true` | Post Provision phase; a node added to an existing instance updates the controller's hosts file as well. |

In controller-only mode the task is skipped on the controller itself.

## Notes

- Each Morpheus Ansible run has only the current node in its inventory, and `instance.configGroup` is
  null for these orders, so `groups[...]` and `hostvars[...]` are not usable here.
- If several workers join at the same time, each one writes the same block to the controller; the last
  write wins and the content is identical.
- cloud-init's `manage_etc_hosts` is not set on the current template. If that changes, `/etc/hosts` is
  rebuilt at boot and the block has to be added to `/etc/cloud/templates/hosts.debian.tmpl` too.
- Re-running the role is a no-op (`blockinfile` and the two line removals are idempotent).

## Verification

```bash
cat /etc/hosts
getent hosts $(hostname)        # must return the node IP, not 127.0.0.1
ping -c1 <other-node-hostname>
```
