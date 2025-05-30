#!/usr/bin/env python3

import paramiko
import sys

# SSH connection details
hostname = "your_server_hostname"
port = 22
username = "your_username"
password = "your_password"  # This will be used for SSH authentication
sudo_password = password  # Using the same password for sudo, change if different

# Create SSH client
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())

try:
    # Connect to the remote server
    print(f"Connecting to {hostname}:{port} as {username}...")
    client.connect(hostname=hostname, port=port, username=username, password=password)
    print("Connection successful")
    
    # Execute the command with sudo password provided through stdin
    print("Executing kubeadm command with sudo...")
    
    # Using sudo -S to read password from stdin
    command = f"echo '{sudo_password}' | sudo -S kubeadm token create --print-join-command"
    stdin, stdout, stderr = client.exec_command(command)
    
    # Check for errors
    error = stderr.read().decode().strip()
    if error and not error.startswith("[sudo]"):  # Ignore the sudo password prompt in stderr
        print(f"Command returned error: {error}")
    
    # Get the output
    join_command = stdout.read().decode().strip()
    
    # Print the join command (for verification)
    print("Join command retrieved:")
    if join_command:
        print(join_command)
    else:
        print("WARNING: Join command is empty, command might have failed")
    
except paramiko.AuthenticationException:
    print("Authentication failed. Check your username and password.")
except paramiko.SSHException as e:
    print(f"SSH error: {str(e)}")
except paramiko.ssh_exception.NoValidConnectionsError:
    print(f"Could not connect to {hostname}:{port}. Check hostname and port.")
except Exception as e:
    print(f"An error occurred: {str(e)}")
    import traceback
    traceback.print_exc()
finally:
    # Close the connection
    print("Closing connection...")
    client.close()

print("Script execution complete")
