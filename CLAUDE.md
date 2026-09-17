# CLAUDE.md — morpheus-ansible

Context for Claude sessions working in this repository. The code is the source of truth; re-read it
before editing, and update this file when the structure changes.

## What this repository does

Self-service provisioning for **HPE Morpheus Enterprise**. An end user orders a Catalog Item, the form
inputs arrive in Ansible as `morpheus['customOptions'][...]`, and Morpheus Workflows run the playbooks
and the Python tasks in `scripts/`. Nothing here is meant to be run by hand with `ansible-playbook`.

Target OS is Ubuntu 24.04. Three stacks:

- Kubernetes — kubeadm, containerd, Flannel, MetalLB v0.14.9, csi-driver-nfs v4.9.0
- MySQL InnoDB Cluster — mysql-apt-config 0.8.36 (shipped in role 12 `files/`), MySQL Shell
- MinIO — `.deb` from dl.min.io, XFS data drives

## Conventions

- Every playbook: `hosts: all`, `become: true`, `ansible_user: ansible`,
  key `/opt/morpheus/.local/.ssh/id_rsa`, interpreter `/usr/bin/python3`, one `role::...` tag per role.
- Primary-node gating: `when: ansible_facts['hostname'] == morpheus['instance']['name']`
  (roles 07, 10, 11, 14, 18). The join playbook uses `!=` for workers.
- Roles are numbered `01`–`18` and use the standard galaxy skeleton.
- `kubernetes.core` modules need the `kubernetes` Python library. Role 07 installs it into
  `/opt/k8s-venv` and runs those tasks with `ansible_python_interpreter: /opt/k8s-venv/bin/python`.
- Python tasks rely on Morpheus code wrapping: a global `morpheus` dict is injected, stdout becomes the
  task result. SSH user and password arrive as `sys.argv[1]` and `sys.argv[2]`.
- Credentials only come from Morpheus custom options or Cypher. Never commit a real password.

## Playbook → roles

| Playbook | Roles | Scope |
|---|---|---|
| `ubuntu-k8-post-provision.yml` | 01, 02, 03, 05, 06 (04 commented out) | all nodes |
| `ubuntu-k8-initilize-cluster.yml` | 07 | control plane |
| `ubuntu-k8-join-node.yml` | 08 | workers |
| `ubuntu-k8-drain-node.yml` | 09 | node being removed |
| `ubuntu-k8-metalb-conf.yml` | 10 | control plane |
| `ubuntu-k8-kubernetes-storage-class.yml` | 11, 18 | control plane |
| `ubuntu-mysql-innodb.yml` | 01, 12, 13, 14 (14 on primary only) | MySQL nodes |
| `ubuntu-minio-object-storage.yml` | 01, 15, 16 (04 and 17 commented out) | MinIO nodes |

Role notes:

- 07 — `kubeadm init --pod-network-cidr={pod_cidr}` only if `admin.conf` is absent; applies Flannel with
  `Network` patched to `pod_cidr` only while the node is NotReady.
- 08 — skips if `/etc/kubernetes/kubelet.conf` exists; asserts `morpheus['results']['k8getjoin']` starts
  with `kubeadm join`; waits for kubelet healthz on :10248.
- 09 — `kubeadm reset --force`, purges kube packages. The drain/delete-node tasks are commented out;
  draining is done by `scripts/drain-k8-command.py`.
- 13 — cluster admin user, removes blank users and the test DB, persists
  `sql_generate_invisible_primary_key`, writes `innodb-mysqld.cnf` (GTID on, 6G buffer pool,
  `server_id` = last IPv4 octet).
- 14 — builds the cluster from `groups[morpheus['instance']['configGroup']]` with a generated MySQL Shell
  JS file (configureInstance → createCluster → addInstance with clone recovery).
- 15 — every disk except sda/vda/xvda/sr0 is formatted XFS and mounted at `/opt/minio/miniodrive{n}`.
- 18 — despite the name, currently installs Helm and repeats role 11's NFS tasks. No S3 StorageClass yet.

## Custom options

`kubernetes_vers`, `pod_cidr`, `k8_master_ip`, `metalb_ip_range`, `nfs_server_ip`, `nfs_share_path`,
`mysql_root_password`, `innodb_admin_user`, `innodb_admin_password`, `innodb_cls_name`,
`minio_root_user`, `minio_root_password`, `minio_s3_api_port`, `minio_console_port`.
`scripts/minio-bucket-create.py` also reads `bucked_name`, `bucked_accesskey`, `bucked_secretkey`,
`bucked_policy` (spelled "bucked" in the option codes).

## scripts/

| Script | Purpose |
|---|---|
| `get-join-command.py` | SSH to the control plane (3 min retry), print the kubeadm join command → result `k8getjoin` |
| `label-k8-command.py` | Label the current server as `node-role.kubernetes.io/worker` |
| `drain-k8-command.py` | `kubectl drain` the current server from the control plane |
| `minio-bucket-create.py` | Bucket, user, policy and access key through `mcli` alias `minios3` |
| `innodb_cluster_setup.py` | Standalone version of role 14, driven by environment variables |
| `inputs.json` | Sample Morpheus option type export |

## Known issues (code review 2026-09-17)

- Role 10 and role 11 call `kubernetes.core` modules with the system Python, where the `kubernetes`
  library is not installed. Role 07 uses the venv; these roles do not.
- Role 18 does not create an S3/MinIO StorageClass (see above).
- Role 13 derives `server_id` from the last IPv4 octet. Nodes in different subnets with the same last
  octet collide, which breaks a later ClusterSet.
- Role 14 wraps createCluster/addInstance in try/catch inside the JS, so mysqlsh exits 0 on failure and
  the task reports success. The JS file containing the password is written without `no_log`.
- Role 16 installs from the `aistor` download path; role 17 installs `.../mc` (a binary, not a `.deb`)
  and uses `mc`, while `minio-bucket-create.py` calls `mcli`.
- `minio-bucket-create.py` steps 3–5 check the `exit_status` left over from step 2.
- The other Python tasks print errors but exit 0, so Morpheus marks a failed task as successful.
  The sudo password is also embedded in the remote command line.
- `scripts/inputs.json` has a trailing comma and is not valid JSON.
- `roles/15-ubuntu-minio-post-provision/vars/main.yml` holds commented-out sample credentials.
- Role 12 ships unused `mysql-apt-config_0.8.33` files; role 13 ships copies it never uses.

## Working rules

- Claude edits files; Emre reviews, commits and pushes.
- Ask before changing the design (for example replacing the SSH-based Python tasks).
