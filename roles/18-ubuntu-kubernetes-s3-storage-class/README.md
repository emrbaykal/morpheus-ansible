# Role 18: ubuntu-kubernetes-s3-storage-class

## Overview

Installs Helm and the NFS CSI driver v4.9.0 via Helm, then creates an NFS-backed `StorageClass` in Kubernetes. This role is similar in purpose to role 11 but uses Helm as the package manager for the CSI driver installation, providing a Helm-managed deployment that integrates with the broader Kubernetes ecosystem.

---

## Purpose

This role provides a Helm-based alternative or complement to role 11's script-based NFS CSI driver installation. Using Helm to manage the CSI driver has several advantages:

- **Upgrade management**: Helm enables in-place upgrades with `helm upgrade` rather than re-running installation scripts
- **Release tracking**: `helm list` provides a view of installed Helm releases and their versions
- **Rollback capability**: Failed upgrades can be rolled back with `helm rollback`
- **Values customization**: Helm charts support extensive customization via values files

Additionally, this role installs Helm itself on the control plane if it is not already present, enabling subsequent Helm-based deployments by operators or other automation.

The StorageClass created by this role allows Kubernetes workloads to dynamically provision NFS-backed PersistentVolumes, enabling stateful applications to store data on shared NFS infrastructure.

---

## What It Does

The role performs the following tasks in order, **executed only on the control plane node**:

1. **Installs Helm**: Downloads and installs the Helm binary using the official Helm installation script (`https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3`). If Helm is already installed, this step is skipped.

2. **Installs the NFS CSI driver via Helm**: Adds the `csi-driver-nfs` Helm repository and installs the NFS CSI driver v4.9.0:
   ```bash
   helm repo add csi-driver-nfs https://raw.githubusercontent.com/kubernetes-csi/csi-driver-nfs/master/charts
   helm repo update
   helm install csi-driver-nfs csi-driver-nfs/csi-driver-nfs \
     --namespace kube-system \
     --version v4.9.0
   ```

3. **Creates the NFS StorageClass**: Applies a `StorageClass` manifest that uses the NFS CSI driver for dynamic provisioning:
   ```yaml
   apiVersion: storage.k8s.io/v1
   kind: StorageClass
   metadata:
     name: nfs-csi
   provisioner: nfs.csi.k8s.io
   parameters:
     server: <nfs_server_ip>
     share: <nfs_share_path>
     nfsvers: "3"
   reclaimPolicy: Delete
   volumeBindingMode: Immediate
   ```

---

## Role Directory Structure

```
roles/18-ubuntu-kubernetes-s3-storage-class/
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

| Variable | Required | Source | Example | Description |
|----------|----------|--------|---------|-------------|
| `nfs_server_ip` | Yes | Morpheus custom option | `192.168.1.20` | IP address of the NFS server. |
| `nfs_share_path` | Yes | Morpheus custom option | `/mnt/nfs/k8s` | The exported NFS path on the NFS server. |

### Morpheus Integration

```
morpheus['customOptions']['nfs_server_ip']    →  nfs_server_ip variable
morpheus['customOptions']['nfs_share_path']   →  nfs_share_path variable
morpheus['instance']['name']                  →  Used to restrict execution to the control plane
```

---

## Execution Guard

This role only executes on the control plane node (hostname matches `morpheus['instance']['name']`). All Helm and `kubectl` commands require access to the kubeconfig which is only available on the control plane.

---

## Relationship with Role 11

Both role 11 and role 18 install the NFS CSI driver and create an NFS StorageClass. They are included together in `ubuntu-k8-kubernetes-storage-class.yml`. In a typical deployment:

| Aspect | Role 11 | Role 18 |
|--------|---------|---------|
| Installation method | Shell script (from GitHub) | Helm chart |
| Helm dependency | Not required | Installs Helm if absent |
| Upgrade mechanism | Re-run install script | `helm upgrade` |
| Release visibility | Not tracked by Helm | Visible in `helm list` |

If both roles run in the same playbook, the second installation may fail if the CSI driver is already installed from the first. Consider enabling only one role depending on your operational preference, or add idempotency checks to handle the case where the driver is already present.

---

## Dependencies

- **Roles 01-08** must have completed and the cluster must be running.
- An NFS server must be configured and exporting the specified share.
- Requires internet access to download the Helm installation script and Helm chart from GitHub.
- Requires `root` or `sudo` privileges (`become: true`).
- `nfs-common` must be installed on all Kubernetes nodes (handled by role 05).

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-k8-kubernetes-storage-class.yml`** — Runs on the control plane along with role 11.

Example playbook snippet:

```yaml
- hosts: k8s_master
  become: true
  roles:
    - role: 11-ubuntu-kubernetes-nfs-storage-class
    - role: 18-ubuntu-kubernetes-s3-storage-class
```

---

## Verification Commands

After the role runs, verify the Helm installation and NFS CSI deployment:

```bash
# Verify Helm is installed
helm version

# Verify the NFS CSI Helm release is installed
helm list -n kube-system | grep csi-driver-nfs

# Verify NFS CSI driver pods are running
kubectl get pods -n kube-system | grep csi-nfs
# Expected: controller and node pods in Running state

# Verify the StorageClass was created
kubectl get storageclass nfs-csi
kubectl describe storageclass nfs-csi

# Test dynamic provisioning
kubectl apply -f - <<TESTEOF
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: test-helm-nfs-pvc
spec:
  accessModes:
    - ReadWriteMany
  storageClassName: nfs-csi
  resources:
    requests:
      storage: 1Gi
TESTEOF

kubectl get pvc test-helm-nfs-pvc
# Expected: STATUS = Bound

# Clean up
kubectl delete pvc test-helm-nfs-pvc

# Check Helm chart values
helm get values csi-driver-nfs -n kube-system
```

---

## Notes and Caveats

- **Conflict with role 11**: If both role 11 and role 18 are executed in the same playbook run and both attempt to install the NFS CSI driver, the second installation will encounter already-existing resources. Add an existence check (`helm status csi-driver-nfs -n kube-system`) or disable the redundant role.
- **Role name**: The role is named `ubuntu-kubernetes-s3-storage-class`, which suggests an S3/MinIO storage class. However, the current implementation deploys an NFS-backed StorageClass, not an S3 CSI driver. If an actual S3 CSI driver (for MinIO-backed Kubernetes persistent storage) is needed, consider the `csi-s3` or `minio-operator` Helm charts.
- **Helm binary location**: The Helm installation script places the binary at `/usr/local/bin/helm`. Verify this path is in `$PATH` for all users who need to run Helm commands.
- **Helm repository caching**: The `helm repo update` step downloads the current chart index from the repository. In air-gapped environments, pre-package the Helm chart with `helm pull` and install it locally.
- **Version pinning**: This role pins the NFS CSI driver to v4.9.0. Helm upgrades to newer versions should be tested in a non-production environment before applying to production clusters.

