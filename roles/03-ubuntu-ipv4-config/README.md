# Role 03: ubuntu-ipv4-config

## Overview

Configures the Linux kernel network stack with the settings required for Kubernetes to function correctly. Specifically, this role enables IPv4 forwarding and configures bridge netfilter support so that iptables rules can inspect bridged network traffic — a requirement for Kubernetes networking and CNI plugins like Flannel.

---

## Purpose

Kubernetes relies on the Linux kernel's networking capabilities for pod-to-pod communication, service routing, and network policy enforcement. Without the correct kernel parameters:

- **IPv4 forwarding disabled**: Pods on different nodes cannot communicate because the kernel will not route packets between network interfaces.
- **Bridge netfilter missing**: iptables rules will not fire for traffic crossing the CNI bridge, breaking service discovery (kube-proxy), NetworkPolicy enforcement, and CNI plugin functionality.

This role applies these parameters persistently via `/etc/sysctl.d/k8s.conf` and ensures the `br_netfilter` kernel module is loaded both immediately and at boot time.

---

## What It Does

The role performs the following tasks in order:

1. **Creates `/etc/sysctl.d/k8s.conf`**: Writes the kernel parameter file with the following settings:
   ```
   net.ipv4.ip_forward = 1
   net.bridge.bridge-nf-call-iptables = 1
   net.bridge.bridge-nf-call-ip6tables = 1
   ```
2. **Loads the `br_netfilter` module**: Runs `modprobe br_netfilter` immediately to make bridge netfilter available in the running kernel without requiring a reboot. Also ensures the module is listed in `/etc/modules-load.d/` for persistence across reboots.
3. **Applies sysctl parameters**: Runs `sysctl --system` to apply all parameters from `/etc/sysctl.d/` immediately, triggering the `apply-sysctl-parameters` handler.

---

## Role Directory Structure

```
roles/03-ubuntu-ipv4-config/
├── README.md               # This file
├── defaults/
│   └── main.yml            # Default variable values (if any)
├── handlers/
│   └── main.yml            # Handlers: apply-sysctl-parameters, load-br_netfilter-module
├── meta/
│   └── main.yml            # Role metadata and dependencies
└── tasks/
    └── main.yml            # Task definitions
```

---

## Kernel Parameters Applied

| Parameter                           | Value | Reason                                                        |
|-------------------------------------|-------|---------------------------------------------------------------|
| `net.ipv4.ip_forward`               | `1`   | Enables IP packet forwarding between interfaces (required for pod routing) |
| `net.bridge.bridge-nf-call-iptables`| `1`   | Enables iptables to process bridged IPv4 traffic (required for kube-proxy and CNI) |
| `net.bridge.bridge-nf-call-ip6tables`| `1`  | Enables iptables to process bridged IPv6 traffic (future-proofing) |

---

## Handlers

| Handler Name                | Trigger Condition              | Action                                  |
|-----------------------------|--------------------------------|-----------------------------------------|
| `apply-sysctl-parameters`   | When k8s.conf is created/changed | `sysctl --system`                     |
| `load-br_netfilter-module`  | When modules config changes    | `modprobe br_netfilter`                 |

---

## Variables

This role does not require any external variables. All kernel parameters are hardcoded as they are standard requirements for all Kubernetes nodes.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| *(none)* | —        | —       | No variables required |

---

## Dependencies

- No role dependencies.
- Requires `root` or `sudo` privileges (`become: true`).
- The `br_netfilter` module must be available in the kernel. On standard Ubuntu 22.04 server installations, this module is included in the default kernel package.

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-k8-post-provision.yml`** — Runs on all nodes as part of pre-provisioning (step 3 of 6).

Example playbook snippet:

```yaml
- hosts: all
  become: true
  roles:
    - role: 03-ubuntu-ipv4-config
```

---

## Verification Commands

After the role runs, verify the kernel configuration with the following commands on the target host:

```bash
# Verify IPv4 forwarding is enabled
sysctl net.ipv4.ip_forward
# Expected output: net.ipv4.ip_forward = 1

# Verify bridge-nf-call-iptables is enabled
sysctl net.bridge.bridge-nf-call-iptables
# Expected output: net.bridge.bridge-nf-call-iptables = 1

# Verify bridge-nf-call-ip6tables is enabled
sysctl net.bridge.bridge-nf-call-ip6tables
# Expected output: net.bridge.bridge-nf-call-ip6tables = 1

# Verify the br_netfilter module is loaded
lsmod | grep br_netfilter
# Expected output: br_netfilter  <size>  0

# Verify the sysctl config file exists
cat /etc/sysctl.d/k8s.conf

# Verify module is configured to load at boot
cat /etc/modules-load.d/br_netfilter.conf
```

---

## Notes and Caveats

- The `br_netfilter` module must be loaded **before** `sysctl --system` is applied; otherwise, the `net.bridge.*` parameters will fail to apply because those kernel namespace paths do not exist until the module is loaded. The role handles this ordering correctly by loading the module first.
- These settings are node-wide and affect all network bridging on the host, not just Kubernetes. On dedicated Kubernetes nodes, this is expected and acceptable.
- The `/etc/sysctl.d/k8s.conf` file takes precedence over default sysctl settings in `/etc/sysctl.conf` for the specific parameters it defines.
- This role is idempotent: if the configuration file already exists with the correct content, Ansible will not make changes and the handlers will not be triggered.
- On Ubuntu 22.04, IPv4 forwarding may already be set to `1` in some cloud images. The role will still create the explicit config file to ensure it persists after kernel or package updates.

