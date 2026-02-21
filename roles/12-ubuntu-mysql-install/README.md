# Role 12: ubuntu-mysql-install

## Overview

Installs MySQL 8.0 on Ubuntu servers using the official MySQL APT configuration package. This role also disables AppArmor (which conflicts with MySQL in some configurations), sets the root password programmatically, and places a version hold on the MySQL server package to prevent unintended upgrades.

---

## Purpose

MySQL InnoDB Cluster requires a consistent MySQL version across all cluster members. This role installs MySQL 8.0 from the official MySQL APT repository (rather than the Ubuntu default, which may ship an older or different version) and configures the root account with a password supplied at runtime via Morpheus.

The version hold ensures that `apt-get upgrade` or `dist-upgrade` operations will not accidentally upgrade MySQL on cluster nodes after the cluster is established, which would require a coordinated rolling upgrade procedure.

---

## What It Does

The role performs the following tasks in order:

1. **Stops and disables AppArmor**: Runs `systemctl stop apparmor && systemctl disable apparmor`. AppArmor can interfere with MySQL's access to its data directory, socket files, and plugin directories, especially when using non-default paths. Disabling it prevents permission errors during MySQL startup.

2. **Copies the MySQL APT config package**: Transfers `files/mysql-apt-config_0.8.36-1_all.deb` to the target host. This Debian package configures the official MySQL APT repository.

3. **Installs the APT config package**: Runs `dpkg -i mysql-apt-config_0.8.36-1_all.deb` in non-interactive mode (with `DEBIAN_FRONTEND=noninteractive`), which adds the MySQL 8.0 repository to the system's APT sources.

4. **Updates the APT cache**: Runs `apt-get update` to include the new MySQL repository.

5. **Installs MySQL packages**: Installs the following packages in non-interactive mode:
   - `mysql-server` — The MySQL 8.0 database server
   - `mysql-client` — MySQL command-line client
   - `mysql-shell` — MySQL Shell (`mysqlsh`) used for InnoDB Cluster management
   - `python3-mysqldb` — Python 3 MySQL adapter (used by Ansible MySQL modules)
   - `libmysqlclient-dev` — MySQL C client library headers

6. **Starts the MySQL service**: Ensures the `mysql` service is running after installation.

7. **Places a version hold on mysql-server**: Runs `apt-mark hold mysql-server` to prevent the MySQL server from being upgraded unintentionally. This is critical for InnoDB Cluster stability.

8. **Creates native password config**: Writes a temporary MySQL configuration file that sets the default authentication plugin to `mysql_native_password`. This is needed to set the root password before the MySQL community fully transitions to `caching_sha2_password`.

9. **Sets the root password**: Connects to MySQL using the `mysql_user` Ansible module (or a `mysql` command) to set the root password to the value of `mysql_root_password`.

The `restart_mysql_service` handler is triggered when configuration changes require a MySQL service restart.

---

## Role Directory Structure

```
roles/12-ubuntu-mysql-install/
├── README.md               # This file
├── defaults/
│   └── main.yml            # Default variable values
├── files/
│   └── mysql-apt-config_0.8.36-1_all.deb   # MySQL APT repository config package
├── handlers/
│   └── main.yml            # Handler: Restart mysql_service
├── meta/
│   └── main.yml            # Role metadata and dependencies
└── tasks/
    └── main.yml            # Task definitions
```

---

## Variables

| Variable | Required | Source | Example | Description |
|----------|----------|--------|---------|-------------|
| `mysql_root_password` | Yes | Morpheus custom option | `SecureRootPass123!` | The password to set for the MySQL `root@localhost` account. |

### Morpheus Integration

```
morpheus['customOptions']['mysql_root_password']   →  mysql_root_password variable
```

---

## Handlers

| Handler Name            | Trigger Condition                  | Action                          |
|-------------------------|------------------------------------|---------------------------------|
| `Restart mysql_service` | When MySQL config files change     | `systemctl restart mysql`       |

---

## Dependencies

- No role dependencies.
- Requires `root` or `sudo` privileges (`become: true`).
- Requires internet access to reach `repo.mysql.com` (APT packages are downloaded from there after the APT config package sets up the repository).
- The `files/mysql-apt-config_0.8.36-1_all.deb` file must be present in the role's `files/` directory. This file is included in the repository.

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-mysql-innodb.yml`** — Runs on all MySQL cluster nodes (step 1 of 3).

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

After the role runs, verify the MySQL installation:

```bash
# Verify MySQL version
mysql --version
# Expected: mysql  Ver 8.0.x ...

# Verify MySQL service is running and enabled
systemctl status mysql
systemctl is-enabled mysql

# Verify MySQL Shell is installed
mysqlsh --version

# Verify root login works with the configured password
mysql -u root -p'<mysql_root_password>' -e "SELECT version();"

# Verify the version hold is in place
apt-mark showhold | grep mysql-server
# Expected: mysql-server

# Verify AppArmor is disabled
systemctl is-enabled apparmor
# Expected: disabled

# Verify python3-mysqldb is installed (required for Ansible MySQL modules)
python3 -c "import MySQLdb; print('OK')"
```

---

## Notes and Caveats

- **AppArmor**: Disabling AppArmor at the system level is a broad change. If your security policy requires AppArmor, consider configuring a proper MySQL AppArmor profile instead of disabling the service entirely. The MySQL AppArmor profile shipped with Ubuntu (`/etc/apparmor.d/usr.sbin.mysqld`) is generally safe to use with default MySQL paths.
- **Authentication plugin**: MySQL 8.0 defaults to `caching_sha2_password` for new accounts. The native password config file deployed in this role ensures compatibility with tools and clients that do not yet support `caching_sha2_password`. After the cluster is established and all clients are updated, you can remove the native password override.
- **DEB package version**: The role ships `mysql-apt-config_0.8.36-1_all.deb`. If a newer version is needed (e.g., to add support for a newer Ubuntu release), download the updated package from `https://dev.mysql.com/downloads/repo/apt/` and replace the file in `files/`.
- **Version hold**: The `apt-mark hold` on `mysql-server` is placed after installation. Be aware that this hold must be removed (`apt-mark unhold mysql-server`) before performing a planned MySQL version upgrade.
- **`python3-mysqldb`**: This package is required for Ansible's `mysql_user`, `mysql_db`, and related modules used in role 13.

