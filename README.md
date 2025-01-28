# Morpheus Ansible Kubernetes Deployment

## Overview
Automated Kubernetes cluster deployment solution using Ansible and Morpheus integration. This project provides a complete workflow for setting up both master and worker nodes in a Kubernetes cluster with containerd runtime on Ubuntu systems.

## Prerequisites
- Ubuntu Server (tested on latest LTS)
- Ansible 2.9+
- Morpheus Infrastructure
- Python 3.x with pip
- Required Python packages:
  - PyYAML
  - jmespath
  - kubernetes

## Role Structure & Workflow

### 1. Basic Ubuntu Configuration (01-ubuntu-config-issue)
- Handles initial Ubuntu system setup
- Tasks:
    - Configures welcome messages

### 2. Swap Management (02-ubuntu-swap-file)
- Manages system swap configuration
- Tasks:
    - Disables swap functionality
    - Removes swap entries from fstab
    - Ensures system stability for Kubernetes

### 3. IPv4 Configuration (03-ubuntu-ipv4-config)
- Configures network settings
- Tasks:
    - Enables IPv4 forwarding
    - Updates sysctl parameters
    - Applies network configuration changes

### 4. System Upgrade (04-ubuntu-os-upgrade)
- Manages system updates
- Tasks:
    - Updates package repositories
    - Performs system upgrade
    - Handles package management cleanup


### 5. Containerd Configuration (05-ubuntu-containerd-conf)
- Sets up container runtime environment
- Tasks:
  - Installs required certificates and dependencies
  - Configures Docker repository and GPG keys
  - Installs and configures containerd.io
  - Manages containerd configuration via config.toml

### 6. Kubernetes Base Setup (06-ubuntu-kubernetes-conf)
- Prepares the system for Kubernetes installation
- Tasks:
  - Configures apt repositories for Kubernetes
  - Installs core Kubernetes packages (kubelet, kubeadm, kubectl)
  - Manages Kubernetes GPG keys and sources

### 7. Master Node Setup (07-ubuntu-kubernetes-initilize-cluster)
- Initializes the Kubernetes control plane
- Tasks:
  - Waits for prerequisite services (2 minutes)
  - Installs Python dependencies for Kubernetes modules
  - Initializes Kubernetes cluster with kubeadm
  - Configures kubectl access
  - Sets up Flannel networking
  - Includes safety delays and status checks

### 8. Worker Node Join (08-ubuntu-kubernetes-join-node)
- Manages worker node addition to the cluster
- Tasks:
  - Checks for existing cluster membership
  - Retrieves join command from master
  - Executes cluster join operation
  - Labels node as worker
  - Includes verification steps

### 9. Node Teardown (09-ubuntu-kubernetes-node-teardown)
- Handles clean removal of nodes from cluster
- Tasks:
  - Drains node workloads
  - Removes node from cluster
  - Uninstalls Kubernetes components
  - Cleans up configurations

## Morpheus Integration
### Custom Options
- `kubernetes_vers`: Kubernetes version specification
- `pod_cidr`: Network CIDR for pod networking
- `k8_master_ip`: Master node IP address
- `hpe-user`: Credentials for node operations

### Security Features
- Uses Morpheus Cypher for sensitive data
- Implements proper permission management
- Secure key handling for repositories

## Usage Instructions
1. Configure Morpheus custom options
2. Ensure network connectivity between nodes

## Network Configuration
- Flannel CNI plugin for pod networking
- Configurable pod CIDR through Morpheus options
- Automatic network setup with proper delays for stabilization

## Maintenance
- Use 09-ubuntu-kubernetes-node-teardown for clean node removal
- Monitor /var/log/syslog for troubleshooting
- Check node status using kubectl get nodes

## Best Practices
- Allow sufficient time between deployment steps
- Verify network connectivity before deployment
- Ensure all prerequisites are met
- Monitor logs during deployment

## Notes
- Designed for Morpheus automation platform
- Includes necessary wait times for service stability
- Implements idempotent operations where possible
- Uses delegate_to for master node operations