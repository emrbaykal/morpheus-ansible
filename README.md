# morpheus-ansible

A comprehensive Ansible automation framework integrated with the [Morpheus Data](https://www.morpheusdata.com/) platform. This repository automates end-to-end infrastructure deployment on Ubuntu servers, covering three major infrastructure components:

- **Kubernetes** — Production-ready cluster with CNI networking, load balancing, and persistent storage
- **MySQL InnoDB Cluster** — High-availability relational database cluster
- **MinIO** — S3-compatible distributed object storage

---

## Table of Contents

- [Architecture](#architecture)
- [Technology Stack](#technology-stack)
- [Prerequisites](#prerequisites)
- [Repository Structure](#repository-structure)
- [Playbooks Overview](#playbooks-overview)
- [Morpheus Integration Parameters](#morpheus-integration-parameters)
- [Role Reference](#role-reference)
- [Python Helper Scripts](#python-helper-scripts)
- [Usage Examples](#usage-examples)
- [Security Features](#security-features)
- [Troubleshooting](#troubleshooting)

---

## Architecture

```
                         +---------------------------+
                         |    Morpheus Data Platform  |
                         |  (Orchestration & CMDB)    |
                         +------------+--------------+
                                      |
                         Ansible Playbooks + Custom Options
                                      |
          +--------------------------++--------------------------+
          |                          |                           |
+---------+----------+  +-----------+----------+  +------------+---------+
|   Kubernetes Cluster|  | MySQL InnoDB Cluster |  |  MinIO Object Store  |
|                     |  |                      |  |                      |
|  +--------------+   |  |  +----------------+  |  |  +----------------+  |
|  | Control Plane|   |  |  | Primary Node   |  |  |  |  MinIO Node 1  |  |
|  | (kubeadm)    |   |  |  | (cluster init) |  |  |  |  (XFS disks)   |  |
|  +--------------+   |  |  +----------------+  |  |  +----------------+  |
|  | Flannel CNI  |   |  |  | Secondary Node |  |  |  |  MinIO Node 2  |  |
|  | MetalLB LB   |   |  |  | (auto-join)    |  |  |  |  (XFS disks)   |  |
|  | NFS CSI      |   |  |  +----------------+  |  |  +----------------+  |
|  +--------------+   |  |  | Secondary Node |  |  |  |  mcli client   |  |
|  | Worker Node 1|   |  |  | (auto-join)    |  |  |  |  alias config  |  |
|  | Worker Node 2|   |  |  +----------------+  |  |  +----------------+  |
|  +--------------+   |  +----------------------+  +----------------------+
+---------------------+
          |
  +-------+-------+
  | Storage Layer |
  | NFS CSI       |
  | StorageClass  |
  +---------------+
```

---

## Technology Stack

| Component          | Technology               | Version      |
|--------------------|--------------------------|--------------|
| Platform           | Morpheus Data            | Latest       |
| Automation         | Ansible                  | Latest       |
| OS                 | Ubuntu Server            | 22.04 LTS    |
| Container Runtime  | containerd               | Latest       |
| Kubernetes         | kubeadm / kubelet / kubectl | Configurable (e.g., 1.28) |
| CNI Plugin         | Flannel                  | Latest       |
| Load Balancer      | MetalLB                  | v0.14.9      |
| NFS CSI Driver     | csi-driver-nfs           | v4.9.0       |
| Package Manager    | Helm                     | Latest       |
| Database           | MySQL                    | 8.0          |
| Cluster Mode       | MySQL InnoDB Cluster     | 8.0          |
| DB Shell           | MySQL Shell (mysqlsh)    | 8.0          |
| Object Storage     | MinIO                    | Latest DEB   |
| MinIO Client       | mcli                     | Latest DEB   |
| Filesystem         | XFS                      | —            |

---

## Prerequisites

### System Requirements

| Resource    | Kubernetes Nodes | MySQL Nodes | MinIO Nodes |
|-------------|-----------------|-------------|-------------|
| OS          | Ubuntu 22.04    | Ubuntu 22.04 | Ubuntu 22.04 |
| CPU         | 2+ cores        | 2+ cores    | 2+ cores    |
| RAM         | 4+ GB           | 8+ GB       | 4+ GB       |
| Disk        | 40+ GB root     | 40+ GB root | 40+ GB root + data disks |
| Network     | Static IP       | Static IP   | Static IP   |

### Software Requirements

- Morpheus Data platform (for variable injection and orchestration)
- Ansible (installed on the Morpheus worker/runner)
- Python 3 with `pip` (installed on target hosts during execution)
- SSH access from Morpheus runner to all target hosts

### Network Requirements

- All nodes must be reachable over SSH (port 22) from the Ansible runner
- Nodes within each cluster must reach each other on required ports:
  - Kubernetes: 6443 (API), 2379-2380 (etcd), 10250-10259 (kubelet/scheduler/controller)
  - MySQL: 3306 (SQL), 33060 (mysqlsh), 33061 (group replication)
  - MinIO: 9000 (S3 API), 9001 (console)
- Internet access or a local mirror for APT packages
- NFS server accessible from Kubernetes nodes (for storage class)
- MetalLB IP range must be free and routable on the local network

---

## Repository Structure

```
morpheus-ansible/
├── README.md                                    # This file
├── test.yml                                     # Test playbook (fact gathering)
├── ubuntu-k8-post-provision.yml                 # K8s pre-installation (roles 01-06)
├── ubuntu-k8-initilize-cluster.yml              # K8s control plane init (role 07)
├── ubuntu-k8-join-node.yml                      # K8s worker join (role 08)
├── ubuntu-k8-drain-node.yml                     # K8s node decommission (role 09)
├── ubuntu-k8-metalb-conf.yml                    # MetalLB load balancer (role 10)
├── ubuntu-k8-kubernetes-storage-class.yml       # NFS + S3 storage classes (roles 11, 18)
├── ubuntu-mysql-innodb.yml                      # MySQL InnoDB Cluster (roles 12-14)
├── ubuntu-minio-object-storage.yml              # MinIO object storage (roles 15-17)
├── group_vars/                                  # Group-level variable files
├── host_vars/                                   # Host-level variable files
├── scripts/                                     # Python helper scripts
│   ├── get-join-command.py                      # Retrieve kubeadm join command
│   ├── innodb_cluster_setup.py                  # Standalone InnoDB cluster setup
│   ├── drain-k8-command.py                      # Drain Kubernetes nodes
│   ├── label-k8-command.py                      # Label Kubernetes nodes
│   └── minio-bucket-create.py                   # Create MinIO buckets
└── roles/
    ├── 01-ubuntu-config-issue/                  # SSH legal warning banner
    ├── 02-ubuntu-swap-file/                     # Disable swap
    ├── 03-ubuntu-ipv4-config/                   # Network stack config
    ├── 04-ubuntu-os-update/                     # OS patching
    ├── 05-ubuntu-containerd-conf/               # containerd runtime
    ├── 06-ubuntu-kubernetes-conf/               # Kubernetes packages
    ├── 07-ubuntu-kubernetes-initilize-cluster/  # Cluster initialization
    ├── 08-ubuntu-kubernetes-join-node/          # Worker node join
    ├── 09-ubuntu-kubernetes-drain-node/         # Node decommission
    ├── 10-ubuntu-kubernetes-metalb-conf/        # MetalLB load balancer
    ├── 11-ubuntu-kubernetes-nfs-storage-class/  # NFS CSI StorageClass
    ├── 12-ubuntu-mysql-install/                 # MySQL 8.0 installation
    ├── 13-ubuntu-mysql-innodb-cluster/          # InnoDB pre-configuration
    ├── 14-ubuntu-mysql-create-innodb-cluster/   # InnoDB cluster creation
    ├── 15-ubuntu-minio-post-provision/          # MinIO disk preparation
    ├── 16-ubuntu-minio-conf/                    # MinIO server setup
    ├── 17-ubuntu-minio-clinet-conf/             # MinIO client (mcli)
    └── 18-ubuntu-kubernetes-s3-storage-class/   # S3/MinIO StorageClass via Helm
```

---

## Playbooks Overview

### Playbook Execution Order

For a complete Kubernetes cluster deployment, execute playbooks in this order:

```
1. ubuntu-k8-post-provision.yml        (all nodes)
2. ubuntu-k8-initilize-cluster.yml     (control plane only)
3. ubuntu-k8-join-node.yml             (worker nodes)
4. ubuntu-k8-metalb-conf.yml           (control plane only)
5. ubuntu-k8-kubernetes-storage-class.yml (control plane only)
```

For MySQL InnoDB Cluster:

```
1. ubuntu-mysql-innodb.yml             (all MySQL nodes)
```

For MinIO Object Storage:

```
1. ubuntu-minio-object-storage.yml     (all MinIO nodes)
```

### Playbook-to-Role Mapping

| Playbook                                  | Roles Executed  | Target Hosts        |
|-------------------------------------------|-----------------|---------------------|
| `test.yml`                                | (none — facts)  | all                 |
| `ubuntu-k8-post-provision.yml`            | 01, 02, 03, 04, 05, 06 | all nodes   |
| `ubuntu-k8-initilize-cluster.yml`         | 07              | control plane       |
| `ubuntu-k8-join-node.yml`                 | 08              | worker nodes        |
| `ubuntu-k8-drain-node.yml`                | 09              | decommissioned nodes|
| `ubuntu-k8-metalb-conf.yml`               | 10              | control plane       |
| `ubuntu-k8-kubernetes-storage-class.yml`  | 11, 18          | control plane       |
| `ubuntu-mysql-innodb.yml`                 | 12, 13, 14      | all MySQL nodes     |
| `ubuntu-minio-object-storage.yml`         | 15, 16, 17      | all MinIO nodes     |

---

## Morpheus Integration Parameters

All parameters are injected by Morpheus Data into Ansible playbooks as extra variables. Configure these in the Morpheus instance custom options before provisioning.

| Morpheus Variable                                  | Used In Role(s) | Example Value              | Description                              |
|----------------------------------------------------|-----------------|----------------------------|------------------------------------------|
| `morpheus['customOptions']['kubernetes_vers']`     | 06              | `1.28`                     | Kubernetes package version               |
| `morpheus['customOptions']['pod_cidr']`            | 07              | `10.244.0.0/16`            | Pod network CIDR for Flannel             |
| `morpheus['customOptions']['k8_master_ip']`        | 07, 08          | `192.168.1.10`             | Control plane IP address                 |
| `morpheus['customOptions']['metalb_ip_range']`     | 10              | `192.168.1.240-192.168.1.250` | MetalLB IP address pool               |
| `morpheus['customOptions']['nfs_server_ip']`       | 11, 18          | `192.168.1.20`             | NFS server IP address                    |
| `morpheus['customOptions']['nfs_share_path']`      | 11, 18          | `/mnt/nfs/k8s`             | NFS export path                          |
| `morpheus['customOptions']['mysql_root_password']` | 12, 13, 14      | `SecurePass123!`           | MySQL root account password              |
| `morpheus['customOptions']['innodb_admin_user']`   | 13, 14          | `clusteradmin`             | InnoDB cluster administrator username    |
| `morpheus['customOptions']['innodb_admin_password']` | 13, 14        | `ClusterPass123!`          | InnoDB cluster administrator password    |
| `morpheus['customOptions']['innodb_cls_name']`     | 14              | `prodCluster`              | InnoDB cluster name                      |
| `morpheus['customOptions']['minio_root_user']`     | 16, 17          | `minioadmin`               | MinIO root user                          |
| `morpheus['customOptions']['minio_root_password']` | 16, 17          | `MinioPass123!`            | MinIO root password                      |
| `morpheus['customOptions']['minio_s3_api_port']`   | 16              | `9000`                     | MinIO S3 API port                        |
| `morpheus['customOptions']['minio_console_port']`  | 16              | `9001`                     | MinIO web console port                   |
| `morpheus['results']['k8getjoin']`                 | 08              | *(generated)*              | kubeadm join command for worker nodes    |
| `morpheus['instance']['name']`                     | 07, 10, 14      | `k8s-master-01`            | Instance hostname (identifies primary)   |
| `morpheus['instance']['configGroup']`              | 14              | `mysql-cluster`            | Config group listing all cluster members |

---

## Role Reference

| # | Role Directory                              | Purpose                                      | README |
|---|---------------------------------------------|----------------------------------------------|--------|
| 01 | `roles/01-ubuntu-config-issue/`            | SSH legal warning banner                     | [README](roles/01-ubuntu-config-issue/README.md) |
| 02 | `roles/02-ubuntu-swap-file/`               | Disable swap (Kubernetes requirement)        | [README](roles/02-ubuntu-swap-file/README.md) |
| 03 | `roles/03-ubuntu-ipv4-config/`             | Kernel network stack for Kubernetes          | [README](roles/03-ubuntu-ipv4-config/README.md) |
| 04 | `roles/04-ubuntu-os-update/`               | System updates and reboot management         | [README](roles/04-ubuntu-os-update/README.md) |
| 05 | `roles/05-ubuntu-containerd-conf/`         | containerd container runtime                 | [README](roles/05-ubuntu-containerd-conf/README.md) |
| 06 | `roles/06-ubuntu-kubernetes-conf/`         | Kubernetes packages (kubelet/kubeadm/kubectl)| [README](roles/06-ubuntu-kubernetes-conf/README.md) |
| 07 | `roles/07-ubuntu-kubernetes-initilize-cluster/` | Kubernetes control plane initialization | [README](roles/07-ubuntu-kubernetes-initilize-cluster/README.md) |
| 08 | `roles/08-ubuntu-kubernetes-join-node/`    | Worker node cluster join                     | [README](roles/08-ubuntu-kubernetes-join-node/README.md) |
| 09 | `roles/09-ubuntu-kubernetes-drain-node/`   | Node decommissioning and cleanup             | [README](roles/09-ubuntu-kubernetes-drain-node/README.md) |
| 10 | `roles/10-ubuntu-kubernetes-metalb-conf/`  | MetalLB load balancer deployment             | [README](roles/10-ubuntu-kubernetes-metalb-conf/README.md) |
| 11 | `roles/11-ubuntu-kubernetes-nfs-storage-class/` | NFS CSI driver and StorageClass        | [README](roles/11-ubuntu-kubernetes-nfs-storage-class/README.md) |
| 12 | `roles/12-ubuntu-mysql-install/`           | MySQL 8.0 installation                       | [README](roles/12-ubuntu-mysql-install/README.md) |
| 13 | `roles/13-ubuntu-mysql-innodb-cluster/`    | MySQL InnoDB Cluster pre-configuration       | [README](roles/13-ubuntu-mysql-innodb-cluster/README.md) |
| 14 | `roles/14-ubuntu-mysql-create-innodb-cluster/` | InnoDB Cluster creation via MySQL Shell | [README](roles/14-ubuntu-mysql-create-innodb-cluster/README.md) |
| 15 | `roles/15-ubuntu-minio-post-provision/`    | MinIO disk preparation (XFS format/mount)    | [README](roles/15-ubuntu-minio-post-provision/README.md) |
| 16 | `roles/16-ubuntu-minio-conf/`             | MinIO server installation and configuration  | [README](roles/16-ubuntu-minio-conf/README.md) |
| 17 | `roles/17-ubuntu-minio-clinet-conf/`      | MinIO client (mcli) setup                    | [README](roles/17-ubuntu-minio-clinet-conf/README.md) |
| 18 | `roles/18-ubuntu-kubernetes-s3-storage-class/` | S3/MinIO StorageClass via Helm         | [README](roles/18-ubuntu-kubernetes-s3-storage-class/README.md) |

---

## Python Helper Scripts

Located in the `scripts/` directory. These are executed by Morpheus workflows or manually by operators.

| Script                      | Purpose                                                                 |
|-----------------------------|-------------------------------------------------------------------------|
| `get-join-command.py`       | SSH to the Kubernetes master, retrieve the `kubeadm join` command, and return it to Morpheus as a result variable (`k8getjoin`). Retries for up to 3 minutes. |
| `innodb_cluster_setup.py`   | Standalone utility for MySQL InnoDB Cluster creation. Can be run independently of the Ansible role for troubleshooting or re-initialization. |
| `drain-k8-command.py`       | Issues `kubectl drain` for a target node via the Kubernetes API. Used prior to node decommissioning. |
| `label-k8-command.py`       | Applies labels to Kubernetes nodes via the API. Used for workload scheduling and node identification. |
| `minio-bucket-create.py`    | Creates MinIO buckets using the MinIO Python SDK or mcli. Used for post-deployment bucket provisioning. |

---

## Usage Examples

### Full Kubernetes Cluster Deployment

```bash
# Step 1: Pre-provision all nodes (system config, swap, network, OS update, containerd, k8s packages)
ansible-playbook ubuntu-k8-post-provision.yml -i inventory/hosts

# Step 2: Initialize the control plane (run only after Step 1 completes on all nodes)
ansible-playbook ubuntu-k8-initilize-cluster.yml -i inventory/hosts

# Step 3: Join worker nodes to the cluster
ansible-playbook ubuntu-k8-join-node.yml -i inventory/hosts

# Step 4: Deploy MetalLB load balancer
ansible-playbook ubuntu-k8-metalb-conf.yml -i inventory/hosts

# Step 5: Install NFS CSI driver and StorageClass
ansible-playbook ubuntu-k8-kubernetes-storage-class.yml -i inventory/hosts
```

### MySQL InnoDB Cluster Deployment

```bash
# Deploy all three roles (install, configure, create cluster) in one playbook
ansible-playbook ubuntu-mysql-innodb.yml -i inventory/hosts
```

### MinIO Object Storage Deployment

```bash
# Deploy all three roles (disk prep, server config, client config) in one playbook
ansible-playbook ubuntu-minio-object-storage.yml -i inventory/hosts
```

### Node Decommissioning

```bash
# Drain and clean up a Kubernetes worker node
ansible-playbook ubuntu-k8-drain-node.yml -i inventory/hosts --limit worker-node-03
```

### Running the Test Playbook

```bash
# Gather facts and write system information to /tmp on all hosts
ansible-playbook test.yml -i inventory/hosts
```

### Passing Morpheus Variables Manually (for testing)

```bash
ansible-playbook ubuntu-k8-post-provision.yml \
  -i inventory/hosts \
  -e "kubernetes_vers=1.28" \
  -e "pod_cidr=10.244.0.0/16"
```

---

## Security Features

- **SSH Legal Banner**: Role 01 deploys a legal warning message to `/etc/issue.net` and configures `sshd` to display it on every connection. This satisfies compliance requirements for unauthorized access warnings.
- **AppArmor Management**: Role 12 disables AppArmor before MySQL installation to prevent profile conflicts, then relies on MySQL's own access controls.
- **Credential Injection via Morpheus**: All passwords and secrets are passed as Morpheus custom options at runtime. No credentials are stored in plaintext within the repository.
- **MySQL Root Password**: Set programmatically during installation using a native password configuration file, and the file is removed after use.
- **Blank User Removal**: Role 13 explicitly removes anonymous MySQL users created by the default installation.
- **Test Database Removal**: Role 13 drops the default `test` database that ships with MySQL.
- **MinIO System User**: Role 16 creates a dedicated `minio` system user with no login shell to run the MinIO service under least privilege.
- **Kubernetes RBAC**: Kubeadm initializes the cluster with RBAC enabled by default.

---

## Troubleshooting

### Kubernetes Issues

**Control plane initialization fails:**
```bash
# Check kubeadm logs
journalctl -xeu kubelet
# Reset and retry
kubeadm reset --force
```

**Worker node cannot join:**
```bash
# Verify join command is correct and not expired (tokens expire after 24 hours)
kubeadm token list
# Generate a new join command
kubeadm token create --print-join-command
```

**Flannel pods not running:**
```bash
kubectl get pods -n kube-flannel
kubectl describe pod -n kube-flannel <pod-name>
```

**MetalLB IP pool not working:**
```bash
kubectl get ipaddresspool -n metallb-system
kubectl get l2advertisement -n metallb-system
kubectl logs -n metallb-system -l app=metallb
```

### MySQL Issues

**InnoDB Cluster creation fails (DNS resolution):**
```bash
# Role 14 retries DNS checks 96 times with 5-second delays (8 minutes total)
# Check /etc/hosts or DNS for all cluster member hostnames
nslookup mysql-node-02
```

**MySQL service not starting:**
```bash
systemctl status mysql
journalctl -xeu mysql
```

**Cluster status check:**
```bash
mysqlsh --uri clusteradmin@localhost:3306 -- dba.getCluster().status()
```

### MinIO Issues

**MinIO service not starting:**
```bash
systemctl status minio
journalctl -xeu minio
# Check disk mounts
df -h /opt/minio/miniodrive*
```

**XFS disk not mounted after reboot:**
```bash
# Check crontab entry for disable-xfs-retry-on-error.sh
crontab -l
# Check /etc/fstab
cat /etc/fstab
```

**mcli alias not working:**
```bash
mcli alias list
mcli admin info minios3
```

### Ansible Connectivity Issues

```bash
# Test SSH connectivity to all hosts
ansible all -i inventory/hosts -m ping

# Check Ansible version
ansible --version

# Run with verbose output
ansible-playbook ubuntu-k8-post-provision.yml -i inventory/hosts -vvv
```

---

## License

This project is maintained for internal infrastructure automation. Refer to your organization's licensing policies for usage terms.

---

## Contributing

1. Follow existing role directory structure conventions
2. All role names must be prefixed with the two-digit sequence number
3. Update the playbook that references your role
4. Add a `README.md` to your role directory following the established format
5. Test changes in a non-production environment before merging

