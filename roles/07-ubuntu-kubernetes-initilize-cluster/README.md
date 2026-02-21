# Role 07: ubuntu-kubernetes-initilize-cluster

## Overview

Initializes the Kubernetes control plane using `kubeadm init` and deploys the Flannel CNI (Container Network Interface) plugin for pod networking. This role transforms a prepared Ubuntu server into a fully functional Kubernetes control plane node.

---

## Purpose

After all nodes have `kubelet`, `kubeadm`, and `containerd` installed (via roles 01-06), the cluster must be bootstrapped. This role performs the critical initialization step on the control plane node only:

- Creates the Kubernetes API server, controller manager, scheduler, and etcd
- Generates cluster certificates and kubeconfig files
- Configures the admin kubeconfig for `kubectl` access
- Deploys Flannel as the pod network CNI so that pods can communicate across nodes

This role runs exclusively on the designated control plane node and is guarded by a hostname check to prevent accidental execution on worker nodes.

---

## What It Does

The role performs the following tasks in order:

1. **Waits 2 minutes**: Pauses to allow all nodes to stabilize and for the container runtime to be fully operational before starting cluster initialization.

2. **Installs Python 3 pip**: Installs `python3-pip` to enable subsequent Python package installations on the control plane.

3. **Installs Python libraries**: Uses pip to install:
   - `PyYAML` — For YAML processing
   - `jmespath` — For JSON/YAML querying in Ansible
   - `kubernetes` — Python Kubernetes client library (used for subsequent Kubernetes API interactions in later roles)

4. **Checks for existing cluster**: Checks whether `/etc/kubernetes/admin.conf` already exists. If it does, the `kubeadm init` step is skipped, making the role idempotent.

5. **Runs `kubeadm init`**: Initializes the cluster with:
   - `--pod-network-cidr` set to the value of `pod_cidr` (e.g., `10.244.0.0/16`)
   - This CIDR must match the Flannel default network configuration

6. **Creates `.kube` directory**: Creates `/root/.kube/` (or the ansible user's home `/.kube/`) to store the kubeconfig.

7. **Copies `admin.conf`**: Copies `/etc/kubernetes/admin.conf` to `~/.kube/config` and sets appropriate ownership so `kubectl` works without `sudo`.

8. **Waits 3 minutes**: Pauses to allow the control plane pods (API server, controller manager, scheduler, etcd) to reach running state before deploying the CNI.

9. **Downloads the latest Flannel manifest**: Downloads the official Flannel deployment manifest from the Flannel GitHub repository.

10. **Updates the Network CIDR in the manifest**: Replaces the default `Network` value in the Flannel ConfigMap with the value of `pod_cidr`, ensuring Flannel manages the correct address space.

11. **Applies the Flannel manifest**: Runs `kubectl apply -f` on the updated manifest to deploy Flannel DaemonSet, ClusterRole, ServiceAccount, and ConfigMap to the cluster.

---

## Role Directory Structure

```
roles/07-ubuntu-kubernetes-initilize-cluster/
├── README.md               # This file
├── defaults/
│   └── main.yml            # Default variable values
├── handlers/
│   └── main.yml            # No handlers in this role
├── meta/
│   └── main.yml            # Role metadata and dependencies
└── tasks/
    └── main.yml            # Task definitions
```

---

## Variables

| Variable | Required | Source | Example | Description |
|----------|----------|--------|---------|-------------|
| `pod_cidr` | Yes | Morpheus custom option | `10.244.0.0/16` | Pod network CIDR for Flannel. Must not overlap with node or service CIDRs. |

### Morpheus Integration

```
morpheus['customOptions']['pod_cidr']   →  pod_cidr variable in tasks
morpheus['instance']['name']            →  Used to identify the control plane node
```

The role uses `when: ansible_hostname == morpheus['instance']['name']` (or equivalent) to restrict execution to the control plane node only.

---

## Execution Guard

This role only executes on the node whose hostname matches `morpheus['instance']['name']`. This prevents `kubeadm init` from running on worker nodes when the playbook targets all nodes in an inventory group.

```yaml
- name: Initialize Kubernetes cluster
  command: kubeadm init --pod-network-cidr={{ pod_cidr }}
  when: ansible_hostname == instance_name
```

---

## Dependencies

- **Role 06** must have completed on all nodes before this role runs.
- **Role 05** must have completed (`containerd` must be running).
- Requires `root` or `sudo` privileges (`become: true`).
- Requires internet access to download the Flannel manifest from GitHub.
- The control plane node must have a resolvable hostname.

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-k8-initilize-cluster.yml`** — Runs on the control plane node only.

Example playbook snippet:

```yaml
- hosts: k8s_master
  become: true
  roles:
    - role: 07-ubuntu-kubernetes-initilize-cluster
```

---

## Verification Commands

After the role runs, verify the cluster initialization on the control plane node:

```bash
# Verify cluster is initialized and nodes are listed
kubectl get nodes
# Expected: control plane node in NotReady or Ready state

# Verify system pods are running (some may take a few minutes)
kubectl get pods -n kube-system
# Expected: etcd, api-server, controller-manager, scheduler pods Running

# Verify Flannel CNI pods are deployed
kubectl get pods -n kube-flannel
# Expected: kube-flannel-ds pod Running on the control plane

# Verify kubeconfig is accessible
kubectl cluster-info

# Verify the pod CIDR was applied
kubectl get configmap -n kube-flannel kube-flannel-cfg -o yaml | grep Network
# Should show the pod_cidr value

# Verify the cluster certificate is valid
openssl x509 -in /etc/kubernetes/pki/apiserver.crt -noout -dates

# Get the join command for worker nodes (used by role 08)
kubeadm token create --print-join-command
```

---

## Notes and Caveats

- **Single execution**: `kubeadm init` should only be run once per cluster. The role's idempotency check on `/etc/kubernetes/admin.conf` prevents re-initialization, but re-running `kubeadm init` on an existing cluster would be destructive.
- **Pod CIDR must match Flannel**: Flannel's default network is `10.244.0.0/16`. If you use a different CIDR, ensure the Flannel ConfigMap is updated accordingly (this role handles the update automatically).
- **Pod CIDR must not overlap**: The pod CIDR must not overlap with the host network, service CIDR (default `10.96.0.0/12`), or MetalLB IP range.
- **Join token expiry**: `kubeadm` join tokens expire after 24 hours by default. The join command must be retrieved (via `get-join-command.py` or manually) and used within this window. New tokens can be generated with `kubeadm token create --print-join-command`.
- **Control plane taint**: By default, `kubeadm init` taints the control plane node to prevent regular workload pods from being scheduled on it. To allow workloads on the control plane (single-node clusters), remove the taint: `kubectl taint nodes --all node-role.kubernetes.io/control-plane-`
- The 3-minute wait before applying Flannel is intentional. The API server must be fully operational to accept the manifest. Removing this wait may result in `kubectl apply` failures.
- Note: the role directory name contains a typo (`initilize` instead of `initialize`) which is preserved for consistency with the existing repository structure.

