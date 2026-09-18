# CLAUDE.md — morpheus-ansible

Context for Claude sessions working in this repository. The code is the source of truth; re-read it
before editing, and update this file when the structure changes.

## What this repository does

Self-service provisioning for **HPE Morpheus Enterprise**. An end user orders a Catalog Item, the form
inputs arrive in Ansible as `morpheus['customOptions'][...]`, and Morpheus Workflows run the playbooks
and the task scripts in `scripts/`. Nothing here is meant to be run by hand with `ansible-playbook`.

Target OS is Ubuntu 24.04. Three stacks:

- Kubernetes — kubeadm, containerd, Flannel (pinned in role 07), MetalLB (role 10), csi-driver-nfs (role 11)
- MySQL InnoDB Cluster — mysql-apt-config 0.8.36 (shipped in role 12 `files/`), MySQL Shell
- RustFS object storage — roles 20-23, replaced MinIO in September 2026

## Conventions

- Every playbook: `hosts: all`, `become: true`, `ansible_user: ansible`,
  key `/opt/morpheus/.local/.ssh/id_rsa`, interpreter `/usr/bin/python3`, one `role::...` tag per role.
- Primary-node gating: `when: ansible_facts['hostname'] == morpheus['instance']['name']`
  (roles 07, 14). The join and drain playbooks use `!=` for workers.
- Defaults hold every tunable: versions, ports, paths, timeouts. No collection dependencies — only
  builtin modules, because the appliance ships a bare ansible-core.
- Credentials only come from Morpheus custom options. Never commit a real password.

## Morpheus behaviour this repository depends on (field-verified 2026-09-17/18)

- `morpheus['instance']['containers']` lists every node of the instance with `hostname` and
  `internalIp`, already during the **Provision** phase. The list is not ordered.
- `instance.configGroup` is null and each Ansible run has only its own node in inventory, so
  `groups[...]` and `hostvars[...]` cannot reach the other nodes. Use `instance.containers`.
- Morpheus supplies the sudo password only for the node the playbook targets, so a `delegate_to`
  target must run with `become: false`.
- `delegate_to` is templated even for a skipped task, so its variable must be a role default, not a
  `set_fact`.
- Phases (`morpheus-docs` library/automation/workflows.rst): Post Provision runs on **every** node when
  a node is added; Pre Provision and Provision run only on the new node; Scale Down runs when a node is
  removed; Teardown runs on VM destroy.
- A **Password**-type Input comes back masked from `GET /api/instances/{id}`, so an appliance-side
  Groovy task can never read it. Form values do reach a Groovy task's `customOptions` binding in clear
  text; only the instance config is masked.
- In a Groovy task `morpheus` is a `com.morpheus.MorpheusAccess` object (applianceUrl, apiAccessToken);
  `instance`, `server` and `customOptions` are separate top-level bindings.
- The appliance JSch offers `ssh-rsa` (SHA-1), which OpenSSH 8.8+ nodes reject. Ansible's own SSH is
  unaffected — which is why node-side work belongs in roles, not in Groovy tasks.
- The Ansible `file` module creates a missing parent directory with the **same** mode, so an implicit
  `/data` became 0750 root and the rustfs user could not traverse it. Create parents explicitly.

## Kubernetes workflow

| Phase | Task |
|---|---|
| Provision | `ubuntu-k8-post-provision.yml` → `ubuntu-k8-initilize-cluster.yml` |
| Post Provision | `ubuntu-k8-join-node.yml` |
| Scale Down | `ubuntu-k8-drain-node.yml` |
| Teardown | `ubuntu-k8-drain-node.yml` |
| Day 2 | `ubuntu-k8-metalb-conf.yml`, `ubuntu-k8-kubernetes-storage-class.yml` |

Roles:

- **19** — writes every instance node (IP + hostname) into `/etc/hosts`, removes the node's own names
  from the `127.0.0.1` line and the stale `127.0.1.1` line. Runs in both the provision and the join
  playbook; no delegation needed because Post Provision runs on every node.
- **01** ssh banner, **02** swapoff + fstab + mask swap.target, **03** overlay/br_netfilter +
  ip_forward + both bridge-nf-call sysctls, **05** containerd config generated with
  `containerd config default` and `SystemdCgroup = true` (`files/config.toml` is unused),
  **06** apt key via `get_url` + `gpg --dearmor`, then `apt-mark hold` on the kube packages.
- **07** — `cloud-init status --wait`, `kubeadm init` with a `creates` guard, wait for `/readyz`, apply
  the pinned Flannel manifest, wait for node Ready. All kubectl, no Python client. Also writes
  `/home/ansible/.kube/config` so delegated tasks need no sudo.
- **08** — the whole join: control plane IP from `instance.containers`; delegated (`become: false`)
  wait for `/readyz`, `kubeadm token create --print-join-command --ttl 1h`, join, reset on failure,
  wait kubelet healthz, label the node as worker, copy the kubeconfig into `/root/.kube/config`.
- **09** — drain + delete the node on the control plane (delegated), then `kubeadm reset --force`,
  remove `/etc/cni/net.d` and both kube configs, purge the packages. The delegated pair sits in a
  block/rescue so a control plane that is already gone never blocks a VM delete.
- **10** — MetalLB: pinned manifest, wait controller deployment and speaker daemonset, then apply
  IPAddressPool/L2Advertisement with retries (the validating webhook refuses the first attempts).
- **11** — NFS CSI: pinned `install-driver.sh` with `KUBECONFIG`, rollout waits, StorageClass from a
  template, block/rescue that recreates the class because its parameters are immutable. `nfs-csi` is
  the default StorageClass; mount option `nfsvers=3`.
- **18** — NOT yet reworked: named S3 storage class but installs Helm and repeats role 11, still uses
  `kubernetes.core`, so it fails. Exclude it or use tags until it is rewritten.

## RustFS workflow

| Phase | Task |
|---|---|
| Provision | `ubuntu-rustfs-object-storage.yml` (roles 01, 20, 21, 22) |
| Operational | `rustfs-create-bucket.yml` (role 23) → `scripts/rustfs_register_bucket.groovy` |

- **20** — every non-system disk is XFS-formatted with label `RUSTFS<n>` and mounted by label at
  `/data/rustfs<n>` through fstab. `/data` is created explicitly 0755.
- **21** — `rustfs` system user, binary in `/usr/local/bin`, `/etc/default/rustfs` and the systemd unit
  from templates, service started, S3 port awaited. `RUSTFS_VOLUMES` becomes `/data/rustfs{0...N-1}`.
- **22** — `rc` CLI plus the alias `rustfs` holding the root credentials.
- **23** — `rc mb`, then `rc admin service-account create <alias> <key> <secret> --name --description
  --policy <file>`, falling back to `service-account update` when the access key exists. The policy
  template grants ListBucket/GetBucketLocation + GetObject (read-only) plus multipart/Put/Delete
  (read-write) on that bucket only — no DeleteBucket, no ListAllMyBuckets.
- `rustfs_register_bucket.groovy` — `POST /api/storage-buckets` with `providerType: s3`, `bucketName`,
  `createBucket: false` and `config` accessKey/secretKey/endpoint/region; the Morpheus name is
  `<instance>-<bucket>` and an existing one is skipped. Endpoint is the container `internalIp` plus the
  port from the instance config.

Lab sizing: one instance, four data disks, plan above the 2 GB minimum.

## Custom options

`kubernetes_vers`, `pod_cidr`, `k8_master_ip`, `metalb_ip_range`, `nfs_server_ip`, `nfs_share_path`,
`mysql_root_password`, `innodb_admin_user`, `innodb_admin_password`, `innodb_cls_name`,
`num_of_k8_nodes`.

RustFS server: `rustfs_access_key`, `rustfs_secret_key`, `rustfs_s3_api_port`, `rustfs_console_port`.
RustFS bucket workflow: `rustfs_bucket_name`, `rustfs_bucket_access_key`, `rustfs_bucket_secret_key`,
`rustfs_bucket_policy` (Select List: `read-write`, `read-only`).

## scripts/

| Script | Status |
|---|---|
| `rustfs_register_bucket.groovy` | Live — second task of the bucket workflow |
| `k8getjoin.groovy`, `k8labelworker.groovy`, `k8getjoin_probe.groovy`, `rustfs_create_bucket.groovy` | Built, then abandoned; can be `git rm`'d |
| `get-join-command.py`, `label-k8-command.py`, `drain-k8-command.py` | Superseded by roles 08 and 09 |
| `innodb_cluster_setup.py` | Standalone version of role 14 |
| `minio-bucket-create.py`, MinIO roles 15/16/17, `ubuntu-minio-object-storage.yml` | To be removed with `git rm` |
| `inputs.json` | Sample option-type export (has a trailing comma, not valid JSON) |

`test.yml` is the diagnostic probe playbook: run it as an Ansible task to dump the Morpheus variables
the roles depend on.

## Known issues

- Role 18 does not create an S3/MinIO StorageClass and still uses `kubernetes.core`.
- Role 13 derives `server_id` from the last IPv4 octet — nodes in different subnets collide.
- Role 14 wraps createCluster/addInstance in try/catch inside the JS, so mysqlsh exits 0 on failure and
  the task reports success. The JS file containing the password is written without `no_log`.
- The remaining Python task scripts print errors but exit 0, so Morpheus marks a failed task successful.
- Role 12 ships unused `mysql-apt-config_0.8.33` files; role 13 ships copies it never uses.

## Working rules

- Claude edits files; Emre reviews, commits and pushes.
- Ask before changing the design.
- Claude does not delete files — it hands over the `git rm` commands.
- Emre sets the version numbers in the Groovy script headers.
- Every change is tested before delivery: `ansible-playbook --syntax-check`, fake
  `kubectl`/`kubeadm`/`rc`/API harnesses, `groovyc` compile, and an md5 comparison between the tested
  copy and the file on disk.
