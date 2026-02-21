# Role 05: ubuntu-containerd-conf

## Overview

Installs and configures `containerd` as the container runtime interface (CRI) for Kubernetes nodes. Containerd is the industry-standard container runtime used by modern Kubernetes clusters and is the recommended replacement for the deprecated Docker daemon in Kubernetes 1.24+.

---

## Purpose

Kubernetes requires a CRI-compliant container runtime on every node to pull and run container images for pods. This role installs `containerd.io` from the official Docker APT repository and deploys a custom configuration file that enables the `SystemdCgroup` driver — a requirement for compatibility with the `systemd` cgroup driver used by `kubelet`.

Without matching cgroup drivers between `containerd` and `kubelet`, the Kubernetes node will fail to start pods reliably, and the `kubelet` will report cgroup-related errors.

---

## What It Does

The role performs the following tasks in order:

1. **Installs prerequisites**: Installs the following packages required for HTTPS APT repository access:
   - `ca-certificates` — Trusted CA certificates for HTTPS validation
   - `curl` — Used to download the Docker GPG signing key
   - `nfs-common` — NFS client libraries needed for NFS-based PersistentVolumes in Kubernetes

2. **Downloads the Docker GPG key**: Downloads the Docker official GPG key from `https://download.docker.com/linux/ubuntu/gpg` and stores it in `/etc/apt/keyrings/docker.asc`. This key is used to verify the authenticity of packages from the Docker APT repository.

3. **Adds the Docker APT repository**: Configures `/etc/apt/sources.list.d/docker.list` to point to the Docker repository for the running Ubuntu release (e.g., `jammy`). The `containerd.io` package is distributed through this repository.

4. **Installs `containerd.io`**: Runs `apt-get install containerd.io` after refreshing the package cache with the new Docker repository.

5. **Deploys the containerd configuration**: Copies `files/config.toml` to `/etc/containerd/config.toml`. This configuration file enables:
   - `SystemdCgroup = true` under the `runc` runtime options, ensuring containerd uses systemd as the cgroup driver
   - Any other site-specific containerd settings

6. **Restarts and enables containerd**: Triggers the `containerd-service` handler, which restarts the `containerd` service to apply the new configuration and enables it to start automatically on boot.

---

## Role Directory Structure

```
roles/05-ubuntu-containerd-conf/
├── README.md               # This file
├── defaults/
│   └── main.yml            # Default variable values (if any)
├── files/
│   └── config.toml         # containerd runtime configuration
├── handlers/
│   └── main.yml            # Handler: containerd-service
├── meta/
│   └── main.yml            # Role metadata and dependencies
└── tasks/
    └── main.yml            # Task definitions
```

---

## Key Files

### `files/config.toml`

The containerd configuration file. The critical section is:

```toml
version = 2

[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runc]
  runtime_type = "io.containerd.runc.v2"

[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runc.options]
  SystemdCgroup = true
```

Setting `SystemdCgroup = true` is mandatory for Kubernetes when the host uses `systemd` as its init system (standard on Ubuntu 22.04).

---

## Handlers

| Handler Name          | Trigger Condition              | Action                                          |
|-----------------------|--------------------------------|-------------------------------------------------|
| `containerd-service`  | When config.toml is deployed   | `systemctl restart containerd && systemctl enable containerd` |

---

## Variables

This role does not require any external variables from Morpheus or inventory files.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| *(none)* | —        | —       | No variables required |

---

## Dependencies

- No role dependencies.
- Requires `root` or `sudo` privileges (`become: true`).
- Requires internet access to reach `download.docker.com` for the GPG key and package downloads. In air-gapped environments, configure a local APT mirror and replace the repository URL.
- Should run after role 04 (OS update) to ensure the system is patched before new software is installed.

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-k8-post-provision.yml`** — Runs on all nodes as part of pre-provisioning (step 5 of 6).

Example playbook snippet:

```yaml
- hosts: all
  become: true
  roles:
    - role: 05-ubuntu-containerd-conf
```

---

## Verification Commands

After the role runs, verify the containerd installation with the following commands on the target host:

```bash
# Verify containerd is installed and get its version
containerd --version

# Verify the containerd service is running and enabled
systemctl status containerd
systemctl is-enabled containerd

# Verify the configuration file was deployed
cat /etc/containerd/config.toml | grep -A5 "SystemdCgroup"
# Expected: SystemdCgroup = true

# Verify containerd is functional (list running containers — should be empty pre-k8s)
ctr containers list

# Verify the Docker APT repository was added
cat /etc/apt/sources.list.d/docker.list

# Verify the Docker GPG key is installed
ls -la /etc/apt/keyrings/docker.asc

# Verify nfs-common is installed (needed for NFS PVs later)
dpkg -l nfs-common
```

---

## Notes and Caveats

- **Cgroup driver must match**: Both `containerd` (via `config.toml`) and `kubelet` (via kubeadm config or `--cgroup-driver` flag) must use the same cgroup driver. This role sets containerd to `systemd`; role 07 initializes Kubernetes with the matching driver.
- **Do not use the Ubuntu APT `containerd` package**: The `containerd` package in Ubuntu's default repositories is an older, stripped-down version that does not include the full CRI plugins needed by Kubernetes. This role explicitly uses `containerd.io` from Docker's repository, which is the correct package.
- The `nfs-common` package is installed here because it is a runtime dependency for NFS-backed PersistentVolumes in Kubernetes. It must be present on all nodes before pods using NFS PVs can be scheduled.
- If the `config.toml` file is updated after initial deployment, re-run this role or manually restart `containerd` on all affected nodes with `systemctl restart containerd`.
- The role is idempotent: if containerd is already installed and the config file is unchanged, no restart will occur.

