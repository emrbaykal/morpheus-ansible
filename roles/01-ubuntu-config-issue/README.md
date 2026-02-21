# Role 01: ubuntu-config-issue

## Overview

Configures a legal warning banner that is displayed to users when they connect to the server via SSH. This role satisfies common compliance and security policy requirements that mandate unauthorized access warnings on all managed systems.

---

## Purpose

Many organizational security policies and regulatory frameworks (ISO 27001, PCI-DSS, CIS Benchmarks) require that servers display a legal notice before granting access. This notice informs users that the system is monitored, that access is restricted to authorized personnel, and that unauthorized access may be subject to legal action.

This role automates the deployment of that banner to every node in the infrastructure, ensuring consistent and policy-compliant messaging across the entire fleet.

---

## What It Does

The role performs the following tasks in order:

1. **Deploys the banner file**: Copies `files/issue.net` to `/etc/issue.net` on the target host. This file contains the full legal warning text.
2. **Configures sshd**: Modifies `/etc/ssh/sshd_config` to set `Banner /etc/issue.net`, instructing the SSH daemon to display the file contents before authentication.
3. **Restarts the SSH service**: Triggers the `restart_ssh_client` handler to reload `sshd` and apply the new configuration without disrupting active sessions (graceful restart).

---

## Role Directory Structure

```
roles/01-ubuntu-config-issue/
├── README.md               # This file
├── defaults/
│   └── main.yml            # Default variable values (if any)
├── files/
│   └── issue.net           # Legal warning banner text file
├── handlers/
│   └── main.yml            # Handler: restart_ssh_client
├── meta/
│   └── main.yml            # Role metadata and dependencies
└── tasks/
    └── main.yml            # Task definitions
```

---

## Key Files

### `files/issue.net`

This file contains the legal warning message displayed at SSH login. Example content:

```
*****************************************************************************
                           AUTHORIZED ACCESS ONLY
*****************************************************************************

This system is the property of [Organization Name]. Access is restricted to
authorized users only. All activity on this system is monitored and recorded.
Unauthorized access or use of this system is strictly prohibited and may be
subject to criminal prosecution.

If you are not an authorized user, disconnect immediately.
*****************************************************************************
```

Modify this file to match your organization's required legal language before deployment.

---

## Variables

This role does not require any external variables. All configuration is managed through the static file `files/issue.net` and hardcoded paths.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| *(none)* | —        | —       | No variables required |

---

## Handlers

| Handler Name         | Trigger Condition          | Action                    |
|----------------------|---------------------------|---------------------------|
| `restart_ssh_client` | When sshd_config changes  | `systemctl restart sshd`  |

---

## Dependencies

- No role dependencies.
- Requires `sshd` to be installed and running on the target host (standard on Ubuntu Server).

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-k8-post-provision.yml`** — Runs on all nodes as part of pre-provisioning (step 1 of 6).

Example playbook snippet:

```yaml
- hosts: all
  become: true
  roles:
    - role: 01-ubuntu-config-issue
```

---

## Verification Commands

After the role runs, verify the configuration with the following commands on the target host:

```bash
# Verify the banner file was deployed
cat /etc/issue.net

# Verify sshd_config has the Banner directive set
grep -i "^Banner" /etc/ssh/sshd_config

# Verify sshd is running
systemctl status sshd

# Test that the banner appears on SSH connection (run from a remote host)
ssh -o "BatchMode=yes" user@<target-host>
# The banner text should appear before the password prompt
```

---

## Notes and Caveats

- The handler uses `systemctl restart sshd`, which terminates and re-establishes the SSH daemon. Existing active SSH sessions managed by a parent process will not be dropped, but this should be tested in your environment before wide deployment.
- On some Ubuntu systems, the SSH service name may be `ssh` rather than `sshd`. Verify with `systemctl list-units | grep ssh` if the handler fails.
- The content of `files/issue.net` should be reviewed and approved by your organization's legal or security team before deployment.
- This role intentionally runs first in the provisioning sequence so that the banner is in place before any service is exposed.
- The `Banner` directive only affects the pre-authentication display. For post-authentication messages, configure `/etc/motd` separately.

