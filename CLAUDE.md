# CLAUDE.md — morpheus-ansible

Context for Claude sessions working in this repository. The code is the source of truth; re-read it
before editing, and update this file when the structure changes.

## What this repository does

Self-service provisioning for **HPE Morpheus Enterprise**. An end user orders a Catalog Item, the form
inputs arrive in Ansible as `morpheus['customOptions'][...]`, and Morpheus Workflows run the playbooks
and the task scripts in `scripts/`. Nothing here is meant to be run by hand with `ansible-playbook`.

Target OS is Ubuntu 24.04. Three stacks:

- Kubernetes — kubeadm, containerd, Flannel (pinned in role 07), MetalLB v0.14.9, csi-driver-nfs v4.9.0
- MySQL InnoDB Cluster — mysql-apt-config 0.8.36 (shipped in role 12 `files/`), MySQL Shell
- MinIO — `.deb` from dl.min.io, XFS data drives

## Conventions

- Every playbook: `hosts: all`, `become: true`, `ansible_user: ansible`,
  key `/opt/morpheus/.local/.ssh/id_rsa`, interpreter `/usr/bin/python3`, one `role::...` tag per role.
- Primary-node gating: `when: ansible_facts['hostname'] == morpheus['instance']['name']`
  (roles 07, 10, 11, 14, 18). The join playbook uses `!=` for workers.
- Roles are numbered `01`–`19` and use the standard galaxy skeleton.
- Task scripts rely on Morpheus code wrapping: a global `morpheus` object is injected and stdout becomes
  the task result. Groovy is the default language (runs in the appliance JVM, no dependencies); the
  remaining Python tasks still take the SSH user and password as `sys.argv[1]` / `sys.argv[2]`.
- Credentials only come from Morpheus custom options or Cypher. Never commit a real password.

## Live Kubernetes workflow (2026-09-17)

| Phase | Order | Task |
|---|---|---|
| Provision | 1 | `ubuntu-k8-post-provision.yml` (roles 19, 01, 02, 03, 05, 06) |
| Provision | 2 | `ubuntu-k8-initilize-cluster.yml` (role 07, control plane only) |
| Post Provision | 1 | `scripts/k8getjoin.groovy` → result `k8getjoin` |
| Post Provision | 2 | `ubuntu-k8-join-node.yml` (roles 19 controller-only, 08) |
| Post Provision | 3 | `scripts/k8labelworker.groovy` |

`README.md` still describes the older order (initialize in Post Provision); the table above is what runs.

## Playbook → roles

| Playbook | Roles | Scope |
|---|---|---|
| `ubuntu-k8-post-provision.yml` | 19, 01, 02, 03, 05, 06 (04 commented out) | all nodes |
| `ubuntu-k8-initilize-cluster.yml` | 07 | control plane |
| `ubuntu-k8-join-node.yml` | 19 (controller only), 08 | workers |
| `ubuntu-k8-drain-node.yml` | 09 | node being removed |
| `ubuntu-k8-metalb-conf.yml` | 10 | control plane |
| `ubuntu-k8-kubernetes-storage-class.yml` | 11, 18 | control plane |
| `ubuntu-mysql-innodb.yml` | 01, 12, 13, 14 (14 on primary only) | MySQL nodes |
| `ubuntu-minio-object-storage.yml` | 01, 15, 16 (04 and 17 commented out) | MinIO nodes |

Role notes:

- 19 — writes every node of the instance (IP + hostname) into `/etc/hosts` from
  `morpheus['instance']['containers']`, removes the node's own names from the `127.0.0.1` line and the
  `127.0.1.1` line inherited from the template. With `hosts_file_controller_only: true` it only
  delegates the block to the controller, which is how a scaled-out instance updates the existing
  controller.
- 02 — `swapoff -a`, comments out every swap line in `/etc/fstab`, removes `/swap.img`, masks
  `swap.target`.
- 03 — loads `overlay` and `br_netfilter`, writes `/etc/sysctl.d/k8s.conf` with `ip_forward` and both
  `bridge-nf-call-ip[6]tables`, applies and then verifies the three values.
- 05 — generates `/etc/containerd/config.toml` with `containerd config default` and flips
  `SystemdCgroup` to true, instead of shipping a fixed file. `files/config.toml` is no longer used and
  can be removed from git.
- 06 — key via `get_url` + `gpg --dearmor` (not the deprecated `apt_key`), then
  `apt-mark hold` on kubelet/kubeadm/kubectl.
- 07 — `cloud-init status --wait`, `kubeadm init` with a `creates` guard, waits for `/readyz`, applies
  the pinned Flannel manifest unconditionally (kubectl apply is idempotent), then waits for the node to
  be Ready and for CoreDNS. Uses plain kubectl; no Python Kubernetes client and no venv.
- 08 — skips if `/etc/kubernetes/kubelet.conf` exists; asserts `morpheus['results']['k8getjoin']` starts
  with `kubeadm join`; on a failed join runs `kubeadm reset --force` and fails with kubeadm's own
  message; waits for kubelet healthz on :10248. Preflight checks are no longer ignored
  (`kubeadm_join_ignore_preflight` is empty by default).
- 09 — `kubeadm reset --force`, purges kube packages. The drain/delete-node tasks are commented out;
  draining is done by `scripts/drain-k8-command.py`.
- 13 — cluster admin user, removes blank users and the test DB, persists
  `sql_generate_invisible_primary_key`, writes `innodb-mysqld.cnf` (GTID on, 6G buffer pool,
  `server_id` = last IPv4 octet).
- 14 — builds the cluster from `groups[morpheus['instance']['configGroup']]` with a generated MySQL Shell
  JS file (configureInstance → createCluster → addInstance with clone recovery).
- 15 — every disk except sda/vda/xvda/sr0 is formatted XFS and mounted at `/opt/minio/miniodrive{n}`.
- 18 — despite the name, currently installs Helm and repeats role 11's NFS tasks. No S3 StorageClass yet.

## Morpheus variables that were field-verified (2026-09-17, test.yml on a 3-node order)

- `morpheus['instance']['containers']` is a list with one entry per node, already complete during the
  **Provision** phase (node status `deploying`). Each entry carries `hostname`, `internalIp`,
  `externalIp`, `sshHost` and a nested `server` map. The list is not ordered.
- The controller is the entry whose `hostname` equals `morpheus['instance']['name']`.
- `morpheus['instance']['configGroup']` is null for these orders, and each Ansible run has only the
  current node in its inventory — so `groups[...]` and `hostvars[...]` cannot be used to reach the
  other nodes. Use `instance.containers` (or `delegate_to` with an IP from it).
- Provisioning leaves `127.0.0.1 <fqdn> <hostname> localhost` and a stale `127.0.1.1` line in
  `/etc/hosts`; cloud-init's `manage_etc_hosts` is not set.

## Custom options

`kubernetes_vers`, `pod_cidr`, `k8_master_ip`, `metalb_ip_range`, `nfs_server_ip`, `nfs_share_path`,
`mysql_root_password`, `innodb_admin_user`, `innodb_admin_password`, `innodb_cls_name`,
`minio_root_user`, `minio_root_password`, `minio_s3_api_port`, `minio_console_port`,
`num_of_k8_nodes` (catalog layout size).
`scripts/minio-bucket-create.py` also reads `bucked_name`, `bucked_accesskey`, `bucked_secretkey`,
`bucked_policy` (spelled "bucked" in the option codes).

## scripts/

| Script | Purpose |
|---|---|
| `k8getjoin.groovy` | Groovy task: control plane join command → result `k8getjoin`. See `scripts/README.md` |
| `k8labelworker.groovy` | Groovy task: label this node as `node-role.kubernetes.io/worker` |
| `k8getjoin_probe.groovy` | One-shot diagnostic for bindings, `instance.containers`, SSH libraries and key |
| `get-join-command.py` | Superseded by `k8getjoin.groovy`; kept for reference |
| `label-k8-command.py` | Superseded by `k8labelworker.groovy`; kept for reference |
| `drain-k8-command.py` | `kubectl drain` the current server from the control plane |
| `minio-bucket-create.py` | Bucket, user, policy and access key through `mcli` alias `minios3` |
| `innodb_cluster_setup.py` | Standalone version of role 14, driven by environment variables |
| `inputs.json` | Sample Morpheus option type export |

`test.yml` is the diagnostic probe playbook: run it as an Ansible task to dump the Morpheus variables
the roles depend on.

## Known issues (code review 2026-09-17)

- Role 10 and role 11 call `kubernetes.core` modules with the system Python, where the `kubernetes`
  library is not installed. Role 07 no longer installs the venv, so these roles need the same kubectl
  treatment.
- Role 18 does not create an S3/MinIO StorageClass (see above).
- Role 13 derives `server_id` from the last IPv4 octet. Nodes in different subnets with the same last
  octet collide, which breaks a later ClusterSet.
- Role 14 wraps createCluster/addInstance in try/catch inside the JS, so mysqlsh exits 0 on failure and
  the task reports success. The JS file containing the password is written without `no_log`.
- Role 16 installs from the `aistor` download path; role 17 installs `.../mc` (a binary, not a `.deb`)
  and uses `mc`, while `minio-bucket-create.py` calls `mcli`.
- `minio-bucket-create.py` steps 3–5 check the `exit_status` left over from step 2.
- The remaining Python tasks print errors but exit 0, so Morpheus marks a failed task as successful.
  The sudo password is also embedded in the remote command line.
- `scripts/inputs.json` has a trailing comma and is not valid JSON.
- `roles/15-ubuntu-minio-post-provision/vars/main.yml` holds commented-out sample credentials.
- Role 12 ships unused `mysql-apt-config_0.8.33` files; role 13 ships copies it never uses.
- Cypher `password/` keys are generated values with a default 32-day lease. If `password/ansible`
  expires, the Groovy tasks fall back to a password that no longer matches. Key authentication is
  tried first for exactly this reason.

## Working rules

- Claude edits files; Emre reviews, commits and pushes.
- Ask before changing the design.
- Emre sets the version numbers in the Groovy script headers.
