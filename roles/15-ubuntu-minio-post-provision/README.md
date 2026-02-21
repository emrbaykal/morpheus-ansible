# Role 15: ubuntu-minio-post-provision

## Overview

Prepares the data disks on MinIO nodes by discovering available block devices, formatting them with the XFS filesystem, mounting them to dedicated MinIO data directories, and configuring XFS error handling for production reliability. This role is the storage foundation that must be in place before the MinIO server can be installed and configured.

---

## Purpose

MinIO's performance and durability characteristics depend on having dedicated raw disks (or block devices) formatted with XFS and mounted with the correct options. MinIO strongly recommends XFS over ext4 for its performance characteristics with object storage workloads.

This role automates the disk preparation process:
- Identifies which disks are data disks (excluding the OS disk)
- Formats them consistently with XFS
- Mounts them with the `noatime` option (reduces unnecessary write I/O)
- Configures XFS error behavior to avoid indefinite retries that could hang the MinIO service

---

## What It Does

The role performs the following tasks in order:

1. **Discovers available data disks**: Uses Ansible facts to find all block devices on the system, excluding the OS disk devices (`sda`, `vda`, `xvda`, `sr0`). The remaining devices are treated as MinIO data disks.

2. **Formats each disk with XFS**: For each discovered data disk, runs:
   ```bash
   mkfs.xfs -L MINIODRIVE<n> -f /dev/<disk>
   ```
   - `-L MINIODRIVE<n>` sets a filesystem label (MINIODRIVE0, MINIODRIVE1, etc.) for easy identification
   - `-f` forces formatting, overwriting any existing filesystem

3. **Creates mount point directories**: Creates directories at `/opt/minio/miniodrive<n>` for each disk, using the same sequential numbering as the disk labels.

4. **Mounts the disks**: Mounts each XFS-formatted disk to its corresponding directory with the `noatime` mount option, which prevents the filesystem from updating file access times on every read — reducing write amplification on busy storage.

5. **Adds entries to `/etc/fstab`**: Persists the mounts across reboots by adding `fstab` entries using the disk label (e.g., `LABEL=MINIODRIVE0`) rather than the device path, which is more robust to disk enumeration changes.

6. **Creates the XFS error retry script**: Writes a script to `/usr/local/sbin/disable-xfs-retry-on-error.sh` that sets `max_retries` to `0` for all XFS error types (`EIO`, `ENOSPC`, `default`) on each MinIO mount point. This prevents XFS from retrying indefinitely on I/O errors, which could block the MinIO process.

7. **Executes the XFS error retry script**: Runs the script immediately after creating it to apply the settings to already-mounted filesystems.

8. **Adds the script to crontab**: Adds a `@reboot` crontab entry for root so the XFS error settings are re-applied every time the system boots (since these settings are not persistent across reboots by default):
   ```
   @reboot /usr/local/sbin/disable-xfs-retry-on-error.sh
   ```

---

## Role Directory Structure

```
roles/15-ubuntu-minio-post-provision/
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

This role does not require external variables from Morpheus. Disk discovery is automatic.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| *(none)* | —        | —       | No variables required |

---

## Disk Discovery Logic

The role identifies data disks by filtering out known OS disk names:

| Excluded Device | Reason |
|-----------------|--------|
| `sda`           | First SCSI disk — typically the OS disk |
| `vda`           | First VirtIO disk — typically the OS disk in KVM/QEMU VMs |
| `xvda`          | First Xen disk — typically the OS disk in Xen VMs |
| `sr0`           | CD-ROM / optical drive — never a data disk |

All remaining block devices (e.g., `sdb`, `sdc`, `vdb`, `vdc`) are treated as MinIO data disks.

---

## XFS Error Handling

MinIO recommends setting XFS `max_retries` to `0` for all error types to prevent the filesystem layer from stalling on I/O errors. The script applies this configuration to all MinIO mount points:

```bash
#!/bin/bash
for mountpoint in /opt/minio/miniodrive*; do
    for errtype in EIO ENOSPC default; do
        echo 0 > /sys/fs/xfs/$(basename $(findmnt -n -o SOURCE $mountpoint))/error/$errtype/max_retries
    done
done
```

This is a runtime kernel setting and must be re-applied after each reboot, which is why the `@reboot` crontab entry is created.

---

## Dependencies

- No role dependencies.
- Requires `root` or `sudo` privileges (`become: true`).
- Data disks must be attached to the VM (unformatted, unpartitioned block devices) before this role runs.
- The `xfsprogs` package must be installed on the target (typically pre-installed on Ubuntu 22.04 or installable via `apt`).

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-minio-object-storage.yml`** — Runs on all MinIO nodes (step 1 of 3).

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

After the role runs, verify disk preparation on the target node:

```bash
# Verify disks are formatted with XFS and mounted
df -hT | grep xfs
# Expected: /opt/minio/miniodrive0, miniodrive1, etc. listed as xfs type

# Verify filesystem labels
lsblk -f
# Expected: MINIODRIVE0, MINIODRIVE1, etc. as filesystem labels

# Verify noatime mount option is active
mount | grep miniodrive
# Expected: Each miniodrive line should include "noatime"

# Verify /etc/fstab entries
grep miniodrive /etc/fstab

# Verify the XFS error script exists and is executable
ls -la /usr/local/sbin/disable-xfs-retry-on-error.sh

# Verify crontab entry for reboot
crontab -l | grep disable-xfs

# Verify XFS error settings are applied (check one disk)
cat /sys/fs/xfs/<device>/error/EIO/max_retries
# Expected: 0
```

---

## Notes and Caveats

- **Destructive operation**: The `mkfs.xfs -f` command will overwrite any existing data on the target disks without warning. Ensure that the disks attached to MinIO nodes are dedicated data disks with no existing data before running this role.
- **Disk count**: MinIO recommends a minimum of 4 drives per node for erasure coding with production redundancy. A single-disk setup is supported for development but does not provide redundancy.
- **`noatime` mount option**: The `noatime` option significantly reduces disk write operations for read-heavy workloads by not updating the access time on every file read. MinIO recommends this option for all data drives.
- **XFS vs ext4**: MinIO's performance testing shows XFS outperforms ext4 for object storage workloads due to its superior handling of large files and multi-threaded writes.
- **fstab label-based mounting**: Using `LABEL=MINIODRIVE<n>` instead of `/dev/sdb` in fstab is more robust: if the OS is rebooted with different disk ordering (e.g., a disk is removed and re-added), label-based mounting ensures the correct disk is mounted to the correct directory.
- The script disabling XFS retries must survive reboots via crontab. If crontab is cleared or overwritten by another process, the XFS retry settings will revert to defaults after the next reboot.

