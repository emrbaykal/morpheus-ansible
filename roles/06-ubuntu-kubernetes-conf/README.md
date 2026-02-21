# Role 06: ubuntu-kubernetes-conf

## Overview

Installs the core Kubernetes packages — `kubelet`, `kubeadm`, and `kubectl` — from the official Kubernetes APT repository. The package version is configurable via a Morpheus custom option, allowing different clusters to run different Kubernetes versions from the same playbook.

---

## Purpose

Before a Kubernetes cluster can be initialized or a node can join a cluster, each node must have the Kubernetes toolchain installed. This role installs exactly the three packages required:

- **`kubelet`**: The node agent that communicates with the API server and manages pod lifecycle on the node.
- **`kubeadm`**: The cluster bootstrap tool used to initialize the control plane and generate join tokens.
- **`kubectl`**: The command-line interface for interacting with the Kubernetes API server.

By installing these from the official Kubernetes APT repository with a pinned version, the role ensures cluster version consistency across all nodes.

---

## What It Does

The role performs the following tasks in order:

1. **Installs prerequisites**: Installs packages required for APT over HTTPS:
   - `apt-transport-https` — Allows APT to use repositories accessed over HTTPS
   - `ca-certificates` — Trusted CA certificates
   - `curl` — Used to download the Kubernetes GPG signing key
   - `gpg` — Used to process the signing key

2. **Downloads the Kubernetes GPG key**: Downloads the signing key for the Kubernetes APT repository. Starting with Kubernetes 1.28, the repository URL includes the minor version number (e.g., `https://pkgs.k8s.io/core:/stable:/v1.28/deb/`). The key is stored in `/etc/apt/keyrings/kubernetes-apt-keyring.gpg`.

3. **Adds the Kubernetes APT repository**: Writes a repository entry to `/etc/apt/sources.list.d/kubernetes.list` pointing to the versioned Kubernetes repository corresponding to `kubernetes_vers`.

4. **Refreshes the APT cache**: Runs `apt-get update` to include the new Kubernetes repository.

5. **Installs Kubernetes packages**: Installs `kubelet`, `kubeadm`, and `kubectl` at the version specified. The package version format follows `1.28.*` notation to install the latest patch release of the specified minor version.

6. **Triggers the apt handler**: The `update-apt-package` handler applies any final package configuration after installation.

---

## Role Directory Structure

```
roles/06-ubuntu-kubernetes-conf/
├── README.md               # This file
├── defaults/
│   └── main.yml            # Default variable values
├── handlers/
│   └── main.yml            # Handler: update-apt-package
├── meta/
│   └── main.yml            # Role metadata and dependencies
└── tasks/
    └── main.yml            # Task definitions
```

---

## Variables

| Variable | Required | Source | Example | Description |
|----------|----------|--------|---------|-------------|
| `kubernetes_vers` | Yes | Morpheus custom option | `1.28` | Kubernetes minor version to install. Used to select the correct APT repository and package version. |

### Morpheus Integration

This variable is sourced from:

```
morpheus['customOptions']['kubernetes_vers']
```

Configure this in the Morpheus instance's custom options before running the provisioning playbook. The value should be the Kubernetes minor version without a patch number (e.g., `1.28`, `1.29`, `1.30`).

---

## Handlers

| Handler Name         | Trigger Condition             | Action                          |
|----------------------|-------------------------------|---------------------------------|
| `update-apt-package` | After package installation    | `apt-get update` (cache refresh) |

---

## Dependencies

- No role dependencies.
- Requires `root` or `sudo` privileges (`become: true`).
- Role 05 (`ubuntu-containerd-conf`) must have been run first, as `kubelet` requires a running container runtime.
- Requires internet access to reach `pkgs.k8s.io` for the GPG key and package downloads.

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-k8-post-provision.yml`** — Runs on all nodes as part of pre-provisioning (step 6 of 6).

Example playbook snippet:

```yaml
- hosts: all
  become: true
  roles:
    - role: 06-ubuntu-kubernetes-conf
```

With Morpheus variable injection, the playbook automatically receives `kubernetes_vers` as an extra variable.

---

## Verification Commands

After the role runs, verify the Kubernetes package installation with the following commands on the target host:

```bash
# Verify all three packages are installed
dpkg -l kubelet kubeadm kubectl

# Check installed versions
kubelet --version
kubeadm version
kubectl version --client

# Verify the Kubernetes APT repository is configured
cat /etc/apt/sources.list.d/kubernetes.list

# Verify the GPG key is installed
ls -la /etc/apt/keyrings/kubernetes-apt-keyring.gpg

# Verify kubelet service is configured (it may not be running yet — normal before cluster init)
systemctl status kubelet
# Expected: Active: activating or failed (waiting for kubeadm config) — this is normal pre-init
```

---

## Notes and Caveats

- **Version pinning**: All three packages (`kubelet`, `kubeadm`, `kubectl`) must be the same minor version. Mixing versions across packages is unsupported and will cause cluster initialization to fail.
- **kubelet will not be running after this role**: The `kubelet` service is configured but requires a cluster configuration (generated by `kubeadm init` or `kubeadm join`) before it can start successfully. A failed or activating kubelet status at this stage is expected and normal.
- **Repository URL structure changed in Kubernetes 1.28**: Prior to 1.28, all Kubernetes packages used a single repository at `apt.kubernetes.io`. Starting with 1.28, the repository URL is versioned by minor version. This role uses the new URL format and is compatible with Kubernetes 1.28 and later. For older versions, the repository URL in the tasks must be adjusted.
- **Package holds**: It is recommended to place holds on `kubelet`, `kubeadm`, and `kubectl` after installation (`apt-mark hold`) to prevent accidental upgrades. Consider adding this as an additional task if your environment requires it.
- If you need to upgrade Kubernetes later, re-run this role with a new `kubernetes_vers` value after removing the existing packages and updating the repository.

