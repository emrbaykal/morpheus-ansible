# Role 11: ubuntu-kubernetes-nfs-storage-class

## Overview

Installs the NFS CSI (Container Storage Interface) driver v4.9.0 for Kubernetes and creates an NFS-backed `StorageClass` named `nfs-csi`. This enables dynamic provisioning of `PersistentVolumes` backed by an NFS server for stateful workloads running in the cluster.

---

## Purpose

Kubernetes stateful applications (databases, caches, message queues) require persistent storage that survives pod restarts and rescheduling. Without a `StorageClass` configured, developers must manually provision `PersistentVolumes` for every stateful workload.

This role installs the NFS CSI driver, which integrates NFS with Kubernetes' dynamic provisioning system. Once deployed, any `PersistentVolumeClaim` referencing the `nfs-csi` storage class will automatically create an NFS-backed directory on the configured NFS server and mount it into the requesting pod.

---

## What It Does

The role performs the following tasks in order, **executed only on the control plane node**:

1. **Downloads the NFS CSI install script**: Downloads the official installation script for NFS CSI driver v4.9.0 from the Kubernetes CSI NFS driver GitHub repository.

2. **Executes the install script**: Runs the downloaded script, which:
   - Creates the `csi-driver-nfs` namespace
   - Deploys the NFS CSI controller (as a `Deployment`)
   - Deploys the NFS node agent (as a `DaemonSet`)
   - Creates required `ServiceAccount`, `ClusterRole`, `ClusterRoleBinding`, and `CSIDriver` objects

3. **Waits for controller pods**: Uses `kubectl wait` to wait for the NFS CSI controller pods to reach `Running` state.

4. **Waits for node agent pods**: Uses `kubectl wait` to wait for the NFS node DaemonSet pods to reach `Running` state on all nodes.

5. **Creates the `nfs-csi` StorageClass**: Applies a `StorageClass` manifest with the following settings:
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

6. **Verifies the StorageClass**: Runs `kubectl get storageclass nfs-csi` to confirm the StorageClass was created successfully.

---

## Role Directory Structure

```
roles/11-ubuntu-kubernetes-nfs-storage-class/
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

## StorageClass Configuration

| Parameter           | Value          | Description                                              |
|---------------------|----------------|----------------------------------------------------------|
| `provisioner`       | `nfs.csi.k8s.io` | The NFS CSI driver provisioner name                    |
| `server`            | `nfs_server_ip` | IP address of the NFS server                           |
| `share`             | `nfs_share_path` | NFS export path on the server                          |
| `nfsvers`           | `3`            | NFS protocol version (3 for broadest compatibility)      |
| `reclaimPolicy`     | `Delete`       | PV is deleted when PVC is deleted                       |
| `volumeBindingMode` | `Immediate`    | PV is provisioned immediately when PVC is created        |

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
morpheus['instance']['name']                  →  Used to restrict execution to control plane
```

---

## Execution Guard

This role only executes on the control plane node (hostname matches `morpheus['instance']['name']`).

---

## Dependencies

- **Roles 01-08** must have completed, and worker nodes must be joined to the cluster.
- An NFS server must be configured and exporting the specified share **before** running this role.
- The NFS share must be accessible (mountable) from all Kubernetes nodes, not just the control plane.
- Requires `root` or `sudo` privileges (`become: true`).
- `nfs-common` package must be installed on all nodes (handled by role 05).

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-k8-kubernetes-storage-class.yml`** — Runs on the control plane along with role 18.

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

After the role runs, verify the NFS CSI driver and StorageClass:

```bash
# Verify CSI driver pods are running
kubectl get pods -n csi-driver-nfs
# Expected: controller and node pods in Running state

# Verify the StorageClass was created
kubectl get storageclass nfs-csi
kubectl describe storageclass nfs-csi

# Test dynamic provisioning with a test PVC
kubectl apply -f - <<TESTEOF
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: test-nfs-pvc
spec:
  accessModes:
    - ReadWriteMany
  storageClassName: nfs-csi
  resources:
    requests:
      storage: 1Gi
TESTEOF

# Verify the PVC is bound
kubectl get pvc test-nfs-pvc
# Expected: STATUS = Bound within a few seconds

# Verify a corresponding PV was created
kubectl get pv

# Check the NFS share on the NFS server for a created directory
ls /mnt/nfs/k8s/

# Clean up test PVC
kubectl delete pvc test-nfs-pvc
```

---

## Notes and Caveats

- **NFS v3 vs v4**: This role configures NFS version 3 (`nfsvers: "3"`). NFS v3 is stateless and has broader compatibility with older NFS servers. If your NFS server supports NFS v4 and you require NFSv4 features (e.g., Kerberos authentication, locking), change `nfsvers` to `4`.
- **Reclaim policy is `Delete`**: When a PVC is deleted, the corresponding NFS subdirectory on the server is also deleted. Change to `Retain` if you need persistent data after PVC deletion.
- **`ReadWriteMany` support**: NFS supports `ReadWriteMany` access mode, allowing multiple pods across multiple nodes to mount the same volume simultaneously. This is useful for shared configuration or log volumes.
- **NFS server must be pre-configured**: This role does not install or configure the NFS server itself. The NFS server setup is assumed to be complete before provisioning.
- **Network latency**: NFS performance is sensitive to network latency. Deploy the NFS server on a low-latency network segment relative to the Kubernetes nodes.
- Role 18 in the same playbook installs a Helm-based variant; if both roles install the same CSI driver, verify there are no conflicts.

