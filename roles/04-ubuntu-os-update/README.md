# Role 04: ubuntu-os-update

## Overview

Performs a full system update and OS patch cycle on Ubuntu servers. If the update process indicates that a reboot is required (e.g., due to a kernel update), the role automatically reboots the server and waits for it to come back online before proceeding.

---

## Purpose

Running Kubernetes, MySQL, or MinIO on an unpatched operating system exposes the infrastructure to known vulnerabilities. This role ensures that all Ubuntu servers in the fleet are fully up-to-date with the latest security patches and package versions before any application software is installed.

Automating OS updates as part of the provisioning pipeline prevents configuration drift and ensures that infrastructure built at different points in time starts from the same patched baseline.

---

## What It Does

The role performs the following tasks in order:

1. **Updates the APT package cache**: Runs `apt-get update` to refresh the list of available packages from all configured repositories.
2. **Performs a dist-upgrade**: Runs `apt-get dist-upgrade -y` to install all available upgrades, including those that may add or remove packages (kernel upgrades, dependency changes). This is more thorough than a standard `apt-get upgrade`.
3. **Checks for reboot requirement**: Inspects the existence of `/var/run/reboot-required`. If this file is present, it indicates that a package installed during the upgrade (typically a kernel update) requires a reboot to take effect.
4. **Reboots the server** (conditional): If `/var/run/reboot-required` exists, issues a reboot command.
5. **Waits for the server to return**: After issuing the reboot, waits up to **900 seconds (15 minutes)** for the host to come back online and be reachable via SSH before continuing with subsequent tasks or plays.

---

## Role Directory Structure

```
roles/04-ubuntu-os-update/
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

This role does not require any external variables. The upgrade behavior is controlled by the fixed task definitions.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| *(none)* | —        | —       | No variables required |

---

## Reboot Logic

The conditional reboot uses the following Ansible pattern:

```yaml
- name: Check if reboot is required
  stat:
    path: /var/run/reboot-required
  register: reboot_required

- name: Reboot the server if required
  reboot:
    reboot_timeout: 900
  when: reboot_required.stat.exists
```

The `reboot` module handles:
- Issuing the reboot command
- Waiting for the SSH port to become unavailable (confirming reboot started)
- Waiting for SSH to become available again (confirming server returned)
- Continuing the play once the server is back

---

## Dependencies

- No role dependencies.
- Requires `root` or `sudo` privileges (`become: true`).
- Requires internet access or a local APT mirror to be configured on the target host.
- Runs after roles 01, 02, and 03 in the provisioning sequence so that kernel network settings applied in role 03 remain effective after the potential reboot in this role.

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-k8-post-provision.yml`** — Runs on all nodes as part of pre-provisioning (step 4 of 6).

Example playbook snippet:

```yaml
- hosts: all
  become: true
  roles:
    - role: 04-ubuntu-os-update
```

---

## Verification Commands

After the role runs, verify the update status with the following commands on the target host:

```bash
# Verify no more updates are available
apt-get update && apt list --upgradable 2>/dev/null
# Should show no upgradable packages (or only held packages)

# Verify no reboot is pending
ls /var/run/reboot-required 2>/dev/null
# Should return: No such file or directory

# Verify the current kernel version (compare with expected latest)
uname -r

# Check the last boot time (should be recent if a reboot occurred)
who -b
# or
uptime
```

---

## Notes and Caveats

- The 900-second (15-minute) wait timeout is intentionally generous to accommodate slow storage backends or systems with long fsck checks on boot. Adjust the `reboot_timeout` value in the task if your infrastructure consistently boots faster or requires more time.
- Running `dist-upgrade` instead of `upgrade` is intentional. Kernel updates, which are the most common reason a reboot is needed, require `dist-upgrade` to install.
- In environments with strict change management policies, you may wish to remove or gate this role. The role can be skipped selectively using Ansible tags or by excluding it from the playbook run.
- If multiple nodes reboot simultaneously, Ansible will wait for each one in the order they appear in the inventory. The total play time will be proportional to the number of nodes that require reboots. Consider using `serial: 1` in the playbook if you need rolling upgrades.
- Unattended upgrades (`unattended-upgrades` package) should be configured separately for ongoing patch management after initial provisioning. This role handles the one-time initial patch cycle.
- Package holds (e.g., the `mysql-server` hold applied in role 12) are set after installation. The `dist-upgrade` in this role runs before any application packages are installed, so there is no risk of it upgrading pinned packages.

