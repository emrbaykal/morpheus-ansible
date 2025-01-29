# Morpheus Ansible Kubernetes Deployment

## Overview
Kubernetes cluster deployment automation using Ansible and Morpheus integration. This project provides a complete workflow for deploying production-ready Kubernetes clusters on Ubuntu systems with containerd runtime.

## Architecture
- **Container Runtime**: containerd
- **Network Plugin**: Flannel CNI
- **Base OS**: Ubuntu Server
- **Automation**: Ansible + Morpheus
- **Security**: GPG key verification, secure repository management

## Prerequisites
### System Requirements
- Ubuntu Server (LTS recommended)
- Minimum 2 CPU cores
- 2GB RAM minimum (4GB recommended)
- Network connectivity between nodes

### Software Requirements
- Ansible 2.9+
- Morpheus Infrastructure
- Python 3.x with pip
- Required Python packages:
  ```
  - PyYAML
  - jmespath
  - kubernetes
  ```

### Additional Network Requirements
- Reserved IP range for MetalLB load balancer services
- No DHCP conflicts in the MetalLB IP range
- L2 network connectivity for MetalLB

## Role Structure
### Pre-Installation Roles (01-04)
1. **01-ubuntu-config-issue**
   - System message configuration
   - Initial setup preparations

2. **02-ubuntu-swap-file**
   - Disables swap (Kubernetes requirement)
   - Modifies /etc/fstab
   - Memory management optimization

3. **03-ubuntu-ipv4-config**
   - Network stack configuration
   - IPv4 forwarding enablement
   - Sysctl parameter optimization

4. **04-ubuntu-os-upgrade**
   - System updates
   - Package management
   - Dependency resolution

### Core Installation Roles (05-06)
5. **05-ubuntu-containerd-conf**
   - containerd runtime installation
   - Docker repository configuration
   - Runtime configuration management
   - SystemdCgroup configuration

6. **06-ubuntu-kubernetes-conf**
   - Kubernetes package installation
   - Repository management
   - Component version control
   - Security key management

### Cluster Management Roles (07-09)
7. **07-ubuntu-kubernetes-initilize-cluster**
   - Control plane initialization
   - Network plugin deployment
   - Certificate management
   - Flannel CNI configuration

8. **08-ubuntu-kubernetes-join-node**
   - Worker node integration
   - Cluster membership management
   - Node labeling
   - Join token handling

9. **09-ubuntu-kubernetes-node-teardown**
   - Node decommissioning
   - Workload drainage
   - Clean uninstallation
   - Configuration cleanup

### Network Services Role (10)
10. **10-ubuntu-kubernetes-metalb-conf**
    - MetalLB load balancer deployment
    - L2 configuration
    - IP address pool management
    - Load balancer service enablement

## Morpheus Integration
### Custom Options
| Option | Description | Example |
|--------|-------------|---------|
| kubernetes_vers | Kubernetes version | 1.28 |
| pod_cidr | Pod network CIDR | 10.244.0.0/16 |
| k8_master_ip | Control plane IP | 192.168.1.10 |
| hpe-user | Node access credentials | stored in cypher |
| metalb_ip_range | MetalLB address pool | 192.168.1.240-192.168.1.250 |

### Security Features
- Morpheus Cypher integration
- Secure credential management
- GPG key verification
- Repository signature validation

## Deployment Process
1. **Pre-installation**
   ```
   Roles: 01-04
   Purpose: System preparation
   ```

2. **Core Installation**
   ```
   Roles: 05-06
   Purpose: Runtime and Kubernetes setup
   ```

3. **Cluster Formation**
   ```
   Roles: 07-08
   Purpose: Cluster creation and expansion
   ```

4. **Network Services**
   ```
   Roles: 10
   Purpose: Load balancer configuration
   ```

## Maintenance Operations
### Node Addition
1. Execute ubuntu-k8-post-provision.yml
2. Run ubuntu-k8-join-node.yml for cluster joining

### Node Removal
1. Execute ubuntu-k8-drain-node.yml
2. Verify cluster health

### Troubleshooting
- Check logs: /var/log/syslog
- Verify node status: kubectl get nodes
- Monitor pod health: kubectl get pods --all-namespaces

## Load Balancer Configuration
### MetalLB Setup
- Automated deployment via role 10-ubuntu-kubernetes-metalb-conf
- L2 advertisement configuration
- IP pool management
- Service validation