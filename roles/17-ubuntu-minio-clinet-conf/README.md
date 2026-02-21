# Role 17: ubuntu-minio-clinet-conf

## Overview

Installs the MinIO Client (`mcli`) command-line tool and configures a named alias pointing to the local MinIO server. This provides operators with immediate command-line access to the MinIO instance for bucket management, data operations, and administration tasks.

---

## Purpose

While the MinIO server (role 16) exposes an S3-compatible HTTP API and a web console, operations teams often need direct command-line access for automation, scripting, and administrative tasks. The MinIO Client (`mcli`) provides a CLI interface equivalent to the AWS CLI (`aws s3`) but purpose-built for MinIO.

This role installs `mcli` and configures a persistent alias (`minios3`) that pre-authenticates to the local MinIO server, so operators can immediately run commands like `mcli ls minios3` or `mcli mb minios3/my-bucket` without re-entering credentials each time.

---

## What It Does

The role performs the following tasks in order:

1. **Installs the `mcli` DEB package**: Installs the MinIO Client binary from a DEB package. After installation, the `mcli` binary is available at `/usr/local/bin/mcli`.

2. **Verifies the installation**: Runs `mcli --version` and registers the output to confirm the binary is installed and working correctly.

3. **Configures the `minios3` alias**: Runs:
   ```bash
   mcli alias set minios3 http://localhost:9000 <minio_root_user> <minio_root_password>
   ```
   This creates a persistent alias in `/root/.mcli/config.json` (or the running user's home directory) that stores the server URL and credentials. The alias name `minios3` is used to prefix all subsequent `mcli` commands.

4. **Lists configured aliases**: Runs `mcli alias list` and displays the output to confirm the `minios3` alias is present and configured.

5. **Lists buckets**: Runs `mcli ls minios3` to list all buckets on the MinIO server, confirming end-to-end connectivity and authentication.

---

## Role Directory Structure

```
roles/17-ubuntu-minio-clinet-conf/
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
| `minio_root_user` | Yes | Morpheus custom option | `minioadmin` | MinIO root user (used as the access key for the `mcli` alias). |
| `minio_root_password` | Yes | Morpheus custom option | `MinioPass123!` | MinIO root password (used as the secret key for the `mcli` alias). |

### Morpheus Integration

```
morpheus['customOptions']['minio_root_user']      →  minio_root_user variable
morpheus['customOptions']['minio_root_password']  →  minio_root_password variable
```

---

## Alias Configuration

The `minios3` alias is stored in the mcli configuration file (typically `~/.mcli/config.json` or `/root/.mcli/config.json` when run as root):

```json
{
  "version": "10",
  "aliases": {
    "minios3": {
      "url": "http://localhost:9000",
      "accessKey": "<minio_root_user>",
      "secretKey": "<minio_root_password>",
      "api": "S3v4",
      "path": "auto"
    }
  }
}
```

---

## Common `mcli` Commands After Configuration

```bash
# List all buckets
mcli ls minios3

# Create a new bucket
mcli mb minios3/my-bucket

# Upload a file
mcli cp /path/to/local/file.txt minios3/my-bucket/file.txt

# Download a file
mcli cp minios3/my-bucket/file.txt /path/to/local/

# List objects in a bucket
mcli ls minios3/my-bucket

# Set bucket policy (public read)
mcli anonymous set download minios3/my-bucket

# Check MinIO server status
mcli admin info minios3

# Mirror a local directory to a bucket
mcli mirror /local/directory/ minios3/my-bucket/

# Remove a bucket and all its contents
mcli rb --force minios3/my-bucket
```

---

## Dependencies

- **Role 16** (`ubuntu-minio-conf`) must have completed and the MinIO server must be running on port 9000 (or the configured S3 API port).
- Requires `root` or `sudo` privileges (`become: true`).
- The MinIO server must be accessible at `http://localhost:9000` from the node where `mcli` is being configured.

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-minio-object-storage.yml`** — Runs on all MinIO nodes (step 3 of 3).

Example playbook snippet:

```yaml
- hosts: minio_nodes
  become: true
  roles:
    - role: 15-ubuntu-minio-post-provision
    - role: 16-ubuntu-minio-conf
    - role: 17-ubuntu-minio-clinet-conf
```

---

## Verification Commands

After the role runs, verify the `mcli` installation and alias configuration:

```bash
# Verify mcli is installed and get its version
mcli --version

# Verify the minios3 alias is configured
mcli alias list
# Expected: minios3 should be listed with URL http://localhost:9000

# Verify connectivity to MinIO via the alias
mcli admin info minios3
# Expected: Server information including disk usage, uptime, and version

# List buckets (should return empty list or existing buckets)
mcli ls minios3

# Run a quick end-to-end test
mcli mb minios3/test-bucket-$(date +%s)
mcli ls minios3
mcli rb --force minios3/test-bucket-*

# Check mcli configuration file directly
cat /root/.mcli/config.json
```

---

## Notes and Caveats

- **Note on role directory name**: The role directory is named `17-ubuntu-minio-clinet-conf` (with `clinet` instead of `client`). This is a typo in the original repository structure and is preserved for consistency.
- **Configuration file location**: When `mcli` is run as `root` (via `become: true`), the configuration is stored in `/root/.mcli/config.json`. If you also need `mcli` access for non-root users, run `mcli alias set minios3 ...` as each user or copy the configuration file.
- **`http` vs `https`**: The alias is configured with `http://localhost:9000`. If TLS is enabled on the MinIO server (see role 16 notes), update this to `https://localhost:9000`. The `mcli` client will need the CA certificate if using self-signed TLS.
- **Credentials in config file**: The `mcli` configuration file stores credentials in plain text. Ensure the file has appropriate permissions (`chmod 600 ~/.mcli/config.json`) and that the MinIO root credentials are rotated periodically.
- **Alternative S3 clients**: The MinIO `minios3` alias is specific to `mcli`. If your workflows use the AWS CLI (`aws s3`), configure it separately with `aws configure` using the MinIO credentials and endpoint URL: `aws --endpoint-url http://localhost:9000 s3 ls`.
- The `minio-bucket-create.py` helper script in the `scripts/` directory provides an alternative programmatic approach to bucket creation that can be called from Morpheus workflows.

