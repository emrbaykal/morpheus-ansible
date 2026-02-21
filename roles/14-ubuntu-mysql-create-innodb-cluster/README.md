# Role 14: ubuntu-mysql-create-innodb-cluster

## Overview

Creates the MySQL InnoDB Cluster by generating and executing a MySQL Shell JavaScript script on the primary node. The script configures each instance for cluster membership, creates the cluster, and adds all secondary nodes using clone-based recovery. This role runs exclusively on the designated primary node.

---

## Purpose

After all MySQL nodes have been installed (role 12) and pre-configured (role 13), the final step is to form the cluster. MySQL InnoDB Cluster formation requires MySQL Shell (`mysqlsh`) with its AdminAPI, which provides a programmatic interface for creating and managing Group Replication clusters.

This role automates the entire cluster creation process: from verifying that all member nodes are DNS-resolvable, through instance configuration, cluster creation, and adding secondary nodes with automatic data synchronization via the clone plugin.

---

## What It Does

The role performs the following tasks in order, **executed only on the primary node**:

1. **DNS resolution check for all cluster hosts**: Performs DNS lookups for every cluster member (retrieved from `morpheus['instance']['configGroup']`). Retries up to **96 times with 5-second delays** (8 minutes total) to accommodate environments where DNS propagation may be slow after provisioning.

2. **Creates `.mylogin.cnf`**: Writes a MySQL login path configuration file (`/root/.mylogin.cnf`) so that `mysqlsh` can connect without exposing the password on the command line. This file is removed at the end of the role.

3. **Generates the MySQL Shell JavaScript script**: Creates a temporary `.js` script file containing the following AdminAPI calls:
   - `dba.configureInstance('user@host1:3306', ...)` — For every cluster member, configures MySQL for Group Replication participation. This sets additional system variables needed by the AdminAPI.
   - `dba.createCluster('cluster_name')` — Creates the cluster on the primary node.
   - `cluster.addInstance('user@host2:3306', {recoveryMethod: 'clone'})` — For each secondary node, adds it to the cluster using the **clone** recovery method, which copies the primary's data set to the secondary before joining Group Replication.

4. **Executes the MySQL Shell script**: Runs `mysqlsh --file <script>.js` to execute the generated script.

5. **Displays script output**: Captures and outputs the MySQL Shell execution results to the Ansible log for auditing and troubleshooting.

6. **Cleans up**: Removes the temporary MySQL Shell script and the `.mylogin.cnf` file.

---

## Role Directory Structure

```
roles/14-ubuntu-mysql-create-innodb-cluster/
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
| `innodb_admin_user` | Yes | Morpheus custom option | `clusteradmin` | InnoDB cluster administrator username. |
| `innodb_admin_password` | Yes | Morpheus custom option | `ClusterPass123!` | InnoDB cluster administrator password. |
| `mysql_root_password` | Yes | Morpheus custom option | `SecureRootPass123!` | MySQL root password for connecting to each instance. |
| `innodb_cls_name` | Yes | Morpheus custom option | `prodCluster` | Name for the InnoDB cluster (used in `dba.createCluster()`). |

### Morpheus Integration

```
morpheus['customOptions']['innodb_admin_user']      →  innodb_admin_user variable
morpheus['customOptions']['innodb_admin_password']  →  innodb_admin_password variable
morpheus['customOptions']['mysql_root_password']    →  mysql_root_password variable
morpheus['customOptions']['innodb_cls_name']        →  innodb_cls_name variable
morpheus['instance']['name']                        →  Identifies the primary node (execution guard)
morpheus['instance']['configGroup']                 →  List of all cluster member hostnames
```

---

## Execution Guard

This role only executes on the primary node (the node whose hostname matches `morpheus['instance']['name']`). The primary node is the one on which `dba.createCluster()` is called. All other nodes are added as secondaries via `cluster.addInstance()`.

---

## Generated MySQL Shell Script Structure

```javascript
// Configure all instances for cluster participation
dba.configureInstance('clusteradmin@mysql-node-01:3306', {
    clusterAdmin: 'clusteradmin',
    clusterAdminPassword: 'ClusterPass123!',
    restart: true
});
dba.configureInstance('clusteradmin@mysql-node-02:3306', { ... });
dba.configureInstance('clusteradmin@mysql-node-03:3306', { ... });

// Create the cluster on the primary
var cluster = dba.createCluster('prodCluster');

// Add secondary nodes with clone recovery
cluster.addInstance('clusteradmin@mysql-node-02:3306', {
    recoveryMethod: 'clone'
});
cluster.addInstance('clusteradmin@mysql-node-03:3306', {
    recoveryMethod: 'clone'
});

// Display cluster status
print(cluster.status());
```

---

## Dependencies

- **Roles 12 and 13** must have completed on all cluster nodes before this role runs.
- All cluster nodes must be DNS-resolvable by hostname from the primary node.
- MySQL must be running on all cluster nodes.
- Port 33061 (Group Replication) and 3306 (MySQL) must be open between all cluster nodes.
- Requires `root` or `sudo` privileges (`become: true`).

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-mysql-innodb.yml`** — Runs on all MySQL cluster nodes, but the cluster creation tasks only execute on the primary (step 3 of 3).

Example playbook snippet:

```yaml
- hosts: mysql_cluster
  become: true
  roles:
    - role: 12-ubuntu-mysql-install
    - role: 13-ubuntu-mysql-innodb-cluster
    - role: 14-ubuntu-mysql-create-innodb-cluster
```

---

## Verification Commands

After the role runs, verify the InnoDB Cluster on the primary node:

```bash
# Connect to MySQL Shell and check cluster status
mysqlsh --uri clusteradmin@localhost:3306 --password='<innodb_admin_password>' \
  -- dba.getCluster().status()

# Expected output should show:
# - clusterName: prodCluster
# - statusText: Cluster is ONLINE and can tolerate up to ONE failure.
# - All members with mode: "R/W" (primary) or "R/O" (secondaries)

# Verify all members are in ONLINE state
mysqlsh --uri clusteradmin@localhost:3306 --password='<innodb_admin_password>' \
  -- dba.getCluster().describe()

# Check Group Replication status directly in MySQL
mysql -u root -p'<mysql_root_password>' -e \
  "SELECT MEMBER_HOST, MEMBER_PORT, MEMBER_STATE, MEMBER_ROLE FROM performance_schema.replication_group_members;"

# Verify replication is working (write on primary, check secondary)
mysql -u root -p'<mysql_root_password>' -e "CREATE DATABASE test_repl;"
# Then on a secondary:
mysql -u root -p'<mysql_root_password>' -e "SHOW DATABASES LIKE 'test_repl';"
# Clean up:
mysql -u root -p'<mysql_root_password>' -e "DROP DATABASE test_repl;"
```

---

## Notes and Caveats

- **Clone recovery**: Using `recoveryMethod: 'clone'` means secondary nodes receive a full data copy from the primary via the MySQL Clone Plugin. For large datasets, this can take a significant amount of time. The process is automatic and does not require manual intervention.
- **DNS retry logic**: The 8-minute DNS retry loop (96 × 5 seconds) is present because, in cloud environments, DNS entries for newly provisioned VMs may take several minutes to propagate. If DNS is not yet available when `configureInstance` runs, the cluster creation will fail. The retry loop prevents this.
- **Script cleanup**: The generated JavaScript and `.mylogin.cnf` files are removed after execution to prevent credentials from being stored on disk. Ensure the role completes successfully before checking for these files; if the role fails mid-execution, manually remove `/root/.mylogin.cnf` and the temporary `.js` script.
- **Cluster name**: The cluster name (`innodb_cls_name`) must be a valid MySQL identifier: start with a letter, contain only alphanumeric characters and underscores, and not exceed 40 characters.
- **Quorum requirements**: A 3-node InnoDB Cluster can tolerate the loss of 1 node while maintaining quorum. A 1-node cluster cannot tolerate any failures. A 2-node cluster requires both nodes to be available (no fault tolerance). For production, always use an odd number of nodes (3, 5, etc.).
- If cluster creation fails, check the MySQL error log (`/var/log/mysql/error.log`) on all nodes and the MySQL Shell output captured by Ansible.

