#!/bin/bash

# Update package lists
sudo apt update

# Install Ansible
sudo apt install -y ansible

# Check if Ansible is installed successfully
if command -v ansible >/dev/null 2>&1; then
    echo "Ansible is installed successfully"
    ansible --version
else
    echo "Ansible installation failed"
    exit 1
fi