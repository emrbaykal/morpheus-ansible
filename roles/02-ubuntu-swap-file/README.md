# Role 02: ubuntu-swap-file

## Overview

Disables and permanently removes the swap file on Ubuntu servers. This is a mandatory prerequisite for Kubernetes, which requires that swap be completely disabled on all nodes in the cluster.

---

## Purpose

Kubernetes assumes that a node's memory is fully available and predictable. When swap is enabled, the Linux kernel may page out memory to disk, which causes unpredictable latency in pod scheduling, pod eviction decisions, and overall cluster stability. The `kubelet` component will refuse to start (or report a warning and degrade in behavior) if swap is detected on the node.

This role ensures that swap is disabled at both the runtime level (immediate effect) and the filesystem table level (persists across reboots).

---

## What It Does

The role performs the following tasks in order:

1. **Checks for swap file existence**: Runs a `stat` check on `/swap.img` to determine whether a swap file is present. The result is registered as a variable.
2. **Disables swap immediately**: If `/swap.img` exists, runs `swapoff -a` to deactivate all active swap immediately, without requiring a reboot.
3. **Removes swap from `/etc/fstab`**: If `/swap.img` exists, removes the swap entry from `/etc/fstab` using a `lineinfile` or `replace` task so the swap is not re-enabled on the next boot.
4. **Deletes the swap file**: If `/swap.img` exists, removes the physical file from disk to free the storage space it occupied.

All tasks that act on swap are conditional — they only execute if the swap file was found in step 1. This makes the role idempotent and safe to run on hosts that have already had swap disabled.

---

## Role Directory Structure

```
roles/02-ubuntu-swap-file/
├── README.md               # This file
├── defaults/
│   └── main.yml            # Default variable values (if any)
├── handlers/
│   └── main.yml            # No handlers defined for this role
├── meta/
│   └── main.yml            # Role metadata and dependencies
└── tasks/
    └── main.yml            # Task definitions
```

---

## Variables

This role does not require any external variables. The swap file path is hardcoded to `/swap.img`, which is the default location used by Ubuntu Server's automated installer.

| Variable | Required | Default      | Description |
|----------|----------|--------------|-------------|
| *(none)* | —        | —            | No variables required |

---

## Conditional Logic

The role uses Ansible's `when` conditional throughout:

```yaml
- name: Check if swap file exists
  stat:
    path: /swap.img
  register: swap_file_check

- name: Disable swap immediately
  command: swapoff -a
  when: swap_file_check.stat.exists

- name: Remove swap from /etc/fstab
  replace:
    path: /etc/fstab
    regexp: '^([^#].*\sswap\s.*)$'
    replace: '# \1'
  when: swap_file_check.stat.exists

- name: Delete swap file
  file:
    path: /swap.img
    state: absent
  when: swap_file_check.stat.exists
```

---

## Dependencies

- No role dependencies.
- Requires `root` or `sudo` privileges (`become: true`).
- This role should run before roles 03-06 and before any Kubernetes installation.

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-k8-post-provision.yml`** — Runs on all nodes as part of pre-provisioning (step 2 of 6).

Example playbook snippet:

```yaml
- hosts: all
  become: true
  roles:
    - role: 02-ubuntu-swap-file
```

---

## Verification Commands

After the role runs, verify that swap is disabled with the following commands on the target host:

```bash
# Verify no swap is currently active
free -h
# The "Swap:" row should show: 0B total, 0B used, 0B free

# Verify no swap devices are active
swapon --show
# This command should return no output

# Verify the swap file has been removed
ls -la /swap.img
# Should return: No such file or directory

# Verify /etc/fstab no longer has an active swap entry
grep -i swap /etc/fstab
# Should return nothing or a commented-out line
```

---

## Notes and Caveats

- This role only handles the default Ubuntu swap file located at `/swap.img`. If your environment uses swap partitions (defined as block devices in `/etc/fstab`) or swap files at other paths, you will need to extend the role to handle those cases.
- Running `swapoff -a` on a heavily loaded system may cause memory pressure if there is significant data in swap at the time. In production environments, ensure workloads are migrated before running this role.
- The role is fully idempotent: if swap has already been disabled and the file removed, all tasks will be skipped cleanly without errors or changes.
- Kubernetes 1.28+ has experimental support for swap via the `NodeSwap` feature gate, but this is not enabled in this repository. All nodes in this framework are expected to have swap completely disabled.
- After running this role, a system reboot (handled by role 04) will confirm that swap does not re-activate on boot.

