# Morpheus Ansible Kubernetes Deployment

## Overview
This repository contains Ansible roles for automated deployment of a Kubernetes cluster on Ubuntu systems using Morpheus. The playbooks handle both master and worker node configurations with containerd as the container runtime.

## Prerequisites
- Ubuntu Server
- Ansible
- Morpheus integration
- Python 3.x

## Role Structure
The repository consists of the following main roles:

1. **ubuntu-containerd-conf**
   - Configures containerd runtime
   - Sets up Docker repository
   - Installs and configures containerd.io

2. **ubuntu-kubernetes-conf**
   - Installs Kubernetes packages (kubelet, kubeadm, kubectl)
   - Configures Kubernetes repositories
   - Manages GPG keys and apt sources

3. **ubuntu-kubernetes-initilize-cluster**
   - Initializes Kubernetes master node
   - Sets up cluster networking with Flannel
   - Configures kubeconfig

4. **ubuntu-kubernetes-join-node**
   - Handles worker node joining process
   - Labels nodes appropriately
   - Validates cluster joining status

## Custom Options
The deployment uses Morpheus custom options:
- `kubernetes_vers`: Kubernetes version
- `pod_cidr`: Pod network CIDR
- `k8_master_ip`: Kubernetes master node IP

## Features
- Automated containerd setup
- Kubernetes cluster initialization
- Flannel network configuration
- Worker node joining automation
- Node labeling
- Proper wait times between critical operations

## Usage
1. Configure Morpheus custom options
2. Execute roles in sequence:
   - containerd configuration
   - kubernetes package installation
   - master node initialization
   - worker node joining

## Note
This setup is designed to work with Morpheus automation platform and includes necessary wait times and checks for proper cluster formation.