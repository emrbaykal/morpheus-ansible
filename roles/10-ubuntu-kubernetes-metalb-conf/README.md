# Role 10: ubuntu-kubernetes-metalb-conf

## Overview

Deploys MetalLB v0.14.9 as a bare-metal load balancer for the Kubernetes cluster and configures an IP address pool and L2 advertisement policy. This enables Kubernetes `Service` resources of type `LoadBalancer` to receive externally accessible IP addresses on bare-metal and on-premises infrastructure.

---

## Purpose

In cloud environments (AWS, GCP, Azure), Kubernetes `LoadBalancer` services are automatically provisioned with a public or VPC-internal IP address by the cloud provider's load balancer controller. In on-premises and bare-metal deployments, no such controller exists by default, and `LoadBalancer` services remain in a `<pending>` state indefinitely.

MetalLB solves this problem by implementing a load balancer controller that assigns IP addresses from a configured pool to `LoadBalancer` services. The L2 mode used in this role announces these IPs via ARP (Address Resolution Protocol) on the local network, making them reachable from other hosts on the same subnet.

---

## What It Does

The role performs the following tasks in order, **executed only on the control plane node**:

1. **Checks for existing MetalLB namespace**: Runs `kubectl get namespace metallb-system` to determine if MetalLB is already installed. If the namespace exists, the installation task is skipped.

2. **Installs MetalLB**: Applies the official MetalLB manifest from GitHub:
   ```
   https://raw.githubusercontent.com/metallb/metallb/v0.14.9/config/manifests/metallb-native.yaml
   ```
   This creates the `metallb-system` namespace and deploys:
   - MetalLB controller `Deployment`
   - MetalLB speaker `DaemonSet`
   - All required `ServiceAccount`, `ClusterRole`, `ClusterRoleBinding`, and `CustomResourceDefinition` resources

3. **Waits for MetalLB pods**: Uses `kubectl wait` with a 300-second timeout to wait for all MetalLB pods to reach the `Running` state before proceeding with configuration. This prevents applying the IP pool before the webhook server is ready.

4. **Creates IPAddressPool**: Applies a `IPAddressPool` custom resource with the IP range specified in `metalb_ip_range`. Example:
   ```yaml
   apiVersion: metallb.io/v1beta1
   kind: IPAddressPool
   metadata:
     name: production-pool
     namespace: metallb-system
   spec:
     addresses:
       - 192.168.1.240-192.168.1.250
   ```

5. **Creates L2Advertisement**: Applies an `L2Advertisement` custom resource that links the IP pool to the L2 advertisement mode, enabling ARP-based IP announcement:
   ```yaml
   apiVersion: metallb.io/v1beta1
   kind: L2Advertisement
   metadata:
     name: production-l2
     namespace: metallb-system
   spec:
     ipAddressPools:
       - production-pool
   ```

---

## Role Directory Structure

```
roles/10-ubuntu-kubernetes-metalb-conf/
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
| `metalb_ip_range` | Yes | Morpheus custom option | `192.168.1.240-192.168.1.250` | IP address range to assign to LoadBalancer services. Must be free and routable on the local network. |

### Morpheus Integration

```
morpheus['customOptions']['metalb_ip_range']   →  metalb_ip_range variable in tasks
morpheus['instance']['name']                   →  Used to identify the control plane node
```

---

## Execution Guard

This role only executes on the control plane node (identified by hostname matching `morpheus['instance']['name']`). All `kubectl` commands must run on the control plane where the kubeconfig is available.

---

## Dependencies

- **Roles 01-07** must have completed before this role.
- The Kubernetes cluster must be initialized and at least one node must be in `Ready` state.
- Requires internet access to download the MetalLB manifest from GitHub. In air-gapped environments, pre-download the manifest and serve it from a local HTTP server.
- Requires `kubectl` and kubeconfig access on the control plane.

---

## Playbook Usage

This role is executed by the following playbook:

**`ubuntu-k8-metalb-conf.yml`** — Runs on the control plane after worker nodes have joined.

Example playbook snippet:

```yaml
- hosts: k8s_master
  become: true
  roles:
    - role: 10-ubuntu-kubernetes-metalb-conf
```

---

## Verification Commands

After the role runs, verify MetalLB deployment and configuration:

```bash
# Verify MetalLB namespace and pods
kubectl get pods -n metallb-system
# Expected: controller and speaker pods in Running state

# Verify the IPAddressPool was created
kubectl get ipaddresspool -n metallb-system
kubectl describe ipaddresspool -n metallb-system production-pool

# Verify the L2Advertisement was created
kubectl get l2advertisement -n metallb-system

# Test LoadBalancer service allocation (create a test service)
kubectl create deployment nginx-test --image=nginx
kubectl expose deployment nginx-test --type=LoadBalancer --port=80
kubectl get svc nginx-test
# Expected: EXTERNAL-IP should be assigned from the MetalLB pool within seconds

# Verify the assigned IP is reachable from another host on the same network
curl http://<assigned-ip>

# Clean up test resources
kubectl delete deployment nginx-test
kubectl delete svc nginx-test
```

---

## Notes and Caveats

- **IP range must not conflict**: The `metalb_ip_range` addresses must not be assigned to any host on the network (via DHCP or static). Coordinate with your network team to reserve this range. Conflicting IPs will cause ARP conflicts and unpredictable connectivity.
- **L2 mode limitations**: MetalLB in L2 mode does not provide true load balancing across multiple nodes for a single service. Instead, one speaker node holds the IP at a time and acts as a gateway. Traffic is evenly distributed only within the cluster via kube-proxy. For true load balancing at the IP level, consider BGP mode.
- **Single node failure**: If the node holding a MetalLB IP fails, MetalLB will announce the IP from another node, but there will be a brief interruption while ARP tables update across the network (typically 10-30 seconds).
- **The 300-second wait** for MetalLB pods is necessary because MetalLB uses a validating webhook to enforce resource validation. Applying `IPAddressPool` before the webhook is ready will cause the apply to fail.
- **Version pinning**: This role deploys MetalLB v0.14.9 specifically. Do not change the version without testing, as MetalLB CRD schemas and API versions change between releases.
- MetalLB requires that `kube-proxy` is running in `iptables` or `ipvs` mode. The kubeadm default is compatible.

