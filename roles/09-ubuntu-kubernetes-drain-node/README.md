# Role 09: ubuntu-kubernetes-drain-node

## Overview

Decommissions a Kubernetes node by performing a `kubeadm reset` to remove all cluster configuration, uninstalls Kubernetes packages, and cleans up residual directories. This role is used when permanently removing a node from the cluster.

---

## Purpose

When a worker node needs to be decommissioned — whether due to hardware replacement, scale-down, or infrastructure changes — the node must be cleanly removed from the Kubernetes cluster to prevent orphaned resources and scheduling errors. This role performs the node-side cleanup: resetting `kubeadm` state, removing Kubernetes binaries, and clearing configuration directories.

Note: The API-side operations (draining workloads off the node and deleting the node object from the cluster) are handled by companion scripts (`drain-k8-command.py`) and should be executed before running this role. Some drain/delete tasks are present in the role but commented out, indicating that the preferred workflow is to handle API-side cleanup through Morpheus scripts.

---

## What It Does

The role performs the following tasks in order:

1. **Resets kubeadm**: Runs `kubeadm reset --force` on the target node. This command:
   - Stops and disables `kubelet`
   - Removes all kubeadm-managed configuration under `/etc/kubernetes/`
   - Flushes iptables rules added by Kubernetes
   - Removes CNI configuration from `/etc/cni/net.d/`
   - Unmounts any volumes mounted by kubelet under `/var/lib/kubelet/`
   - The `--force` flag skips confirmation prompts for automation compatibility

2. **Removes Kubernetes packages**: Uninstalls `kubelet`, `kubeadm`, and `kubectl` via `apt-get remove --purge`, ensuring no residual configuration from the packages remains.

3. **Removes the `.kube` directory**: Deletes `~/.kube/` (for both root and any other configured user) to remove kubeconfig files and cached credentials.

**Commented-out tasks** (present but disabled):
- `kubectl drain <node>` — Evicts pods and marks the node as unschedulable (handled by `drain-k8-command.py`)
- `kubectl delete node <node>` — Removes the node object from the cluster (handled by `drain-k8-command.py`)

---

## Role Directory Structure

```
roles/09-ubuntu-kubernetes-drain-node/
├── README.md               # This file
├── defaults/
│   └── main.yml            # Default variable values
├── handlers/
│   └── main.yml            # No handlers defined for this role
├── meta/
│   └── main.yml            # Role metadata and dependencies
└── tasks/
    └── main.yml            # Task definitions
```

---

## Variables

This role does not require any external variables.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| *(none)* | —        | —       | No variables required |

---

## Dependencies

- No role dependencies within this repository.
- Requires `root` or `sudo` privileges (`become: true`).
- The API-side node drain and deletion should be completed **before** running this role:
  1. Run `drain-k8-command.py` to evict pods and cordon the node
  2. Verify workloads have migrated to other nodes
  3. Run this Ansible role to clean up the node itself

---

## Recommended Decommissioning Workflow

```
Step 1: Run drain-k8-command.py (from Morpheus or manually)
        → kubectl drain <node-name> --ignore-daemonsets --delete-emptydir-data
        → kubectl delete node <node-name>

Step 2: Verify workloads have rescheduled
        → kubectl get pods -A -o wide | grep <node-name>
        → Should return no results

Step 3: Run the decommissioning playbook
        → ansible-playbook ubuntu-k8-drain-node.yml -i inventory --limit <node-name>

Step 4: Decommission the VM in Morpheus
```

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-k8-drain-node.yml`** — Runs on nodes being permanently removed from the cluster.

Example playbook snippet:

```yaml
- hosts: decommission_targets
  become: true
  roles:
    - role: 09-ubuntu-kubernetes-drain-node
```

It is strongly recommended to use `--limit` to restrict this playbook to the specific node being decommissioned:

```bash
ansible-playbook ubuntu-k8-drain-node.yml -i inventory/hosts --limit worker-node-03
```

---

## Verification Commands

After the role runs, verify the cleanup on the decommissioned node:

```bash
# Verify kubeadm reset completed (no /etc/kubernetes directory)
ls /etc/kubernetes/
# Expected: No such file or directory

# Verify Kubernetes packages are removed
dpkg -l kubelet kubeadm kubectl 2>/dev/null
# Expected: No packages found

# Verify .kube directory is removed
ls ~/.kube/
# Expected: No such file or directory

# Verify kubelet service is gone
systemctl status kubelet
# Expected: Unit kubelet.service could not be found

# On the control plane, verify the node no longer appears
kubectl get nodes
# Expected: The decommissioned node should not be listed
```

---

## Notes and Caveats

- **This operation is irreversible**: `kubeadm reset --force` removes all cluster configuration from the node. To re-add the node to the cluster (or any cluster), you must run the full provisioning sequence from role 01 through role 08 again.
- **Always drain before resetting**: Do not run this role before draining the node and deleting its cluster object. Pods on the node will be terminated abruptly if the node is reset without a proper drain, which may cause data loss for stateful workloads.
- **iptables rules**: `kubeadm reset` flushes the iptables rules it added, but may leave chains. If the node is being repurposed (not retired), manually flush iptables: `iptables -F && iptables -t nat -F && iptables -t mangle -F && iptables -X`.
- **CNI cleanup**: The `/etc/cni/net.d/` directory is cleaned by `kubeadm reset`, but network interfaces created by the CNI (e.g., `flannel.1`, `cni0`) may persist. These can be removed manually with `ip link delete flannel.1` and `ip link delete cni0`.
- **Container images**: `kubeadm reset` does not remove cached container images. To free disk space, run `crictl rmi --prune` or `ctr image list` and `ctr image remove` for each image.
- Use `--limit` when running this playbook to avoid accidentally running decommissioning tasks on healthy cluster nodes.

