# Role 13: ubuntu-mysql-innodb-cluster

## Overview

Pre-configures each MySQL node for participation in a MySQL InnoDB Cluster. This role creates the cluster administrator user, removes insecure defaults, enables invisible primary keys, and writes the InnoDB-specific server configuration including a unique `server-id`, binary logging, and GTID-based replication settings.

---

## Purpose

MySQL InnoDB Cluster (Group Replication) requires specific configuration on every node before the cluster can be created. Nodes must have:

- A dedicated administrator account with full privileges for cluster management
- Unique `server-id` values to distinguish nodes in binary logging
- Binary logging enabled in the correct format for Group Replication
- GTIDs (Global Transaction Identifiers) enabled for consistent replication
- InnoDB buffer pool sized appropriately for the workload
- The MySQL default "blank user" and "test database" removed for security

This role performs all of these pre-configuration steps on every cluster node so that role 14 can create the cluster without encountering prerequisite failures.

---

## What It Does

The role performs the following tasks in order:

1. **Creates the cluster admin user**: Creates a MySQL user (`innodb_admin_user`) with the password `innodb_admin_password` and grants it `ALL PRIVILEGES` with `GRANT OPTION` on all databases (`*.*`). This account is used by MySQL Shell to manage the cluster.

2. **Removes blank MySQL users**: Deletes any accounts where the username is an empty string. These are created by MySQL's default installation and represent a security risk.

3. **Drops the test database**: Removes the `test` database that ships with MySQL by default. This database is accessible without authentication in some MySQL configurations and should always be removed in production.

4. **Enables invisible primary keys**: Executes:
   ```sql
   SET PERSIST sql_generate_invisible_primary_key=1;
   ```
   This MySQL 8.0.30+ feature automatically adds an invisible primary key to tables that do not have one, which is required for Group Replication compatibility.

5. **Writes `/etc/mysql/conf.d/innodb.cnf`**: Creates the InnoDB cluster configuration file with:
   ```ini
   [mysqld]
   server-id = <last_octet_of_host_ip>
   innodb_buffer_pool_size = 6G
   log_bin = mysql-bin
   binlog_format = ROW
   gtid_mode = ON
   enforce_gtid_consistency = ON
   ```
   The `server-id` is derived from the last octet of the host's IP address (e.g., host IP `192.168.1.45` → `server-id = 45`), ensuring uniqueness across cluster nodes.

6. **Restarts MySQL**: Triggers the `Restart mysql_db` handler to apply the new configuration file.

---

## Role Directory Structure

```
roles/13-ubuntu-mysql-innodb-cluster/
├── README.md               # This file
├── defaults/
│   └── main.yml            # Default variable values
├── handlers/
│   └── main.yml            # Handler: Restart mysql_db
├── meta/
│   └── main.yml            # Role metadata and dependencies
└── tasks/
    └── main.yml            # Task definitions
```

---

## Variables

| Variable | Required | Source | Example | Description |
|----------|----------|--------|---------|-------------|
| `innodb_admin_user` | Yes | Morpheus custom option | `clusteradmin` | Username for the InnoDB cluster administrator account. |
| `innodb_admin_password` | Yes | Morpheus custom option | `ClusterPass123!` | Password for the InnoDB cluster administrator account. |
| `mysql_root_password` | Yes | Morpheus custom option | `SecureRootPass123!` | MySQL root password (required to connect and execute these setup tasks). |

### Morpheus Integration

```
morpheus['customOptions']['innodb_admin_user']      →  innodb_admin_user variable
morpheus['customOptions']['innodb_admin_password']  →  innodb_admin_password variable
morpheus['customOptions']['mysql_root_password']    →  mysql_root_password variable
```

---

## Handlers

| Handler Name        | Trigger Condition                | Action                    |
|---------------------|----------------------------------|---------------------------|
| `Restart mysql_db`  | When innodb.cnf is created/changed | `systemctl restart mysql` |

---

## Server-ID Generation

The `server-id` value in the InnoDB configuration is automatically generated from the host's IP address:

```
Host IP: 192.168.1.45  →  server-id = 45
Host IP: 10.0.2.101    →  server-id = 101
```

This is achieved using Ansible's `ansible_default_ipv4.address` fact:

```yaml
server-id = {{ ansible_default_ipv4.address.split('.')[-1] }}
```

If multiple nodes have the same last IP octet, their `server-id` values will conflict, which will cause Group Replication to fail. In this case, use a more unique ID generation strategy.

---

## Dependencies

- **Role 12** (`ubuntu-mysql-install`) must have completed successfully.
- Requires `root` or `sudo` privileges (`become: true`).
- MySQL must be running before this role executes.
- The `python3-mysqldb` package (installed in role 12) is required for the Ansible `mysql_user` and `mysql_db` modules.

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-mysql-innodb.yml`** — Runs on all MySQL cluster nodes (step 2 of 3).

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

After the role runs, verify the InnoDB pre-configuration:

```bash
# Verify MySQL restarted with the new configuration
systemctl status mysql

# Verify server-id and binary logging are configured
mysql -u root -p'<mysql_root_password>' -e "SHOW VARIABLES LIKE 'server_id';"
mysql -u root -p'<mysql_root_password>' -e "SHOW VARIABLES LIKE 'log_bin';"
mysql -u root -p'<mysql_root_password>' -e "SHOW VARIABLES LIKE 'gtid_mode';"
mysql -u root -p'<mysql_root_password>' -e "SHOW VARIABLES LIKE 'enforce_gtid_consistency';"
mysql -u root -p'<mysql_root_password>' -e "SHOW VARIABLES LIKE 'binlog_format';"

# Verify the cluster admin user was created
mysql -u root -p'<mysql_root_password>' -e "SELECT User, Host FROM mysql.user WHERE User='<innodb_admin_user>';"

# Verify blank users were removed
mysql -u root -p'<mysql_root_password>' -e "SELECT User, Host FROM mysql.user WHERE User='';"
# Expected: Empty set

# Verify test database was dropped
mysql -u root -p'<mysql_root_password>' -e "SHOW DATABASES LIKE 'test';"
# Expected: Empty set

# Verify innodb.cnf exists
cat /etc/mysql/conf.d/innodb.cnf

# Verify invisible primary keys setting
mysql -u root -p'<mysql_root_password>' -e "SELECT @@sql_generate_invisible_primary_key;"
# Expected: 1

# Verify buffer pool size (reported in bytes)
mysql -u root -p'<mysql_root_password>' -e "SHOW VARIABLES LIKE 'innodb_buffer_pool_size';"
# Expected: 6442450944 (6 GB in bytes)
```

---

## Notes and Caveats

- **Buffer pool size**: The 6 GB InnoDB buffer pool is set for nodes with at least 8 GB of RAM. Adjust the `innodb_buffer_pool_size` value in the template if your nodes have more or less memory. A general guideline is 70-80% of available RAM for dedicated database servers.
- **server-id uniqueness**: If the last octet of two nodes' IP addresses is the same (e.g., `10.0.1.10` and `10.0.2.10`), their `server-id` values will both be `10`, causing Group Replication to reject one of them. Use a different ID scheme if your network subnets may collide in this way.
- **GTID and binary logging**: Enabling GTIDs and binary logging will slightly increase write overhead and disk usage on each node. This is expected and necessary for Group Replication.
- **Invisible primary keys**: The `sql_generate_invisible_primary_key` setting is a `PERSIST`ed variable, meaning it survives MySQL restarts and is stored in `mysqld-auto.cnf`. If you need to disable it later, use `SET PERSIST sql_generate_invisible_primary_key=0`.
- The cluster admin user is created at `%` (any host) to allow MySQL Shell to connect from the primary node to the secondary nodes during cluster formation in role 14.

