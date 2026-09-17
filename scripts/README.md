# scripts/ — Morpheus task scripts

Registered as Morpheus Tasks and executed between the Ansible steps of a Workflow. Groovy is the
default: it runs inside the appliance JVM with no dependency on Python, virtualenv or pip.

## Groovy tasks (Kubernetes)

| File | Morpheus task | Phase | Result |
|---|---|---|---|
| `k8getjoin.groovy` | K8 Get Join Command | Post Provision, before the join playbook | Single Value, code `k8getjoin` |
| `k8labelworker.groovy` | K8 Label Node As Worker | Post Provision, after the join playbook | none |
| `k8getjoin_probe.groovy` | diagnostic, run by hand | any | none |

### Task settings

- TYPE: Groovy Script, SOURCE: Local, paste the file body.
- `k8getjoin.groovy`: RESULT TYPE = **Single Value**, CODE = **k8getjoin**. Role 08 reads
  `morpheus['results']['k8getjoin']`, so the code must not change.
- `k8labelworker.groovy`: no result type needed; it prints one status line.
- Both are run against every node of the instance and do nothing on the control plane.

### How they reach the control plane

The control plane is the instance node whose hostname equals `morpheus['instance']['name']`. Its
address is taken from `morpheus['instance']['containers']` (field `internalIp`), so a stale DNS record
on the appliance cannot send the connection to another host. After connecting, the answering host's
`hostname` is compared with the expected name and the task fails if they differ.

### Authentication

1. The SSH key Ansible already uses, `/opt/morpheus/.local/.ssh/id_rsa`, with `sudo -n`.
2. If the key is unreadable or rejected, the password from Cypher `password/ansible`, read over REST
   with the **caller's** token (`morpheus.apiAccessToken`), with `sudo -S`.
3. If the key works but sudo still asks for a password, the Cypher password is fetched for sudo only.

Consequences:

- With key authentication nothing is read from Cypher, so the ordering user needs no Cypher permission.
- If the fallback is used, the ordering user's role needs read access to `password/ansible`.
- JSch versions before the mwiede fork sign with `ssh-rsa` (SHA-1), which OpenSSH 8.8 and newer reject.
  `k8getjoin_probe.groovy` prints the JSch version and the key header, so the path in use can be
  confirmed. A key in OpenSSH format (`BEGIN OPENSSH PRIVATE KEY`) is not readable by JSch 0.1.x
  either; a PEM key is.
- Cypher `password/` keys are generated, with a default lease of 32 days. Moving the key to `secret/`
  with no lease avoids a silent expiry; change `CYPHER_KEY` at the top of both scripts if you do.

### Timing

`k8getjoin.groovy` waits up to 15 minutes for the control plane API server to answer `/readyz`. It
deliberately does not check for `/etc/kubernetes/admin.conf`: kubeadm writes that file early, before
the API server is up, so a file check can let `kubeadm token create` run too soon. The bootstrap token
is created with `--ttl 1h`, because the join command is stored in the Morpheus task result.

### Failures

Everything is reported by throwing, so the Morpheus task fails and the message on the task is the
cause: SSH authentication, sudo rights, a stale address, the API server not coming up, a Cypher error.
Authentication failures are not retried; network errors are retried for 3 minutes.

## Python tasks

| Script | Status |
|---|---|
| `get-join-command.py` | Superseded by `k8getjoin.groovy`. Kept for reference. |
| `label-k8-command.py` | Superseded by `k8labelworker.groovy`. Kept for reference. |
| `drain-k8-command.py` | In use. `kubectl drain` the current server from the control plane. |
| `minio-bucket-create.py` | In use. Bucket, user, policy and access key via `mcli`. |
| `innodb_cluster_setup.py` | Standalone version of role 14, driven by environment variables. |
| `inputs.json` | Sample Morpheus option type export. |

The Python tasks take the SSH user and password as `sys.argv[1]` and `sys.argv[2]`, print errors and
exit 0 — so Morpheus marks a failed run as successful. The Groovy tasks replace that behaviour.
