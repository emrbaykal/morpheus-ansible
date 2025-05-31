import paramiko
import sys

# SSH connection details
controller_node = morpheus['instance']['name']
worker_node = morpheus['server']['hostname']
port = 22
username = sys.argv[1]
password = sys.argv[2]
sudo_password = password  # Using the same password for sudo

# Create SSH client
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())

try:
    # Connect to the remote server
    client.connect(hostname=controller_node, port=port, username=username, password=password)

    # Using sudo -S to read password from stdin
    command = f"echo '{sudo_password}' | sudo -S kubectl drain {worker_node} --ignore-daemonsets --delete-emptydir-data --force"
    stdin, stdout, stderr = client.exec_command(command)

    # Check for errors
    error = stderr.read().decode().strip()
    if error and not error.startswith("[sudo]"):  # Ignore the sudo password prompt in stderr
        print(f"Command returned error: {error}")

    # Get the output
    drain_command = stdout.read().decode().strip()

    if drain_command:
        print(drain_command)
    else:
        print("WARNING: Drain command is empty, command might have failed")

except paramiko.AuthenticationException:
    print("Authentication failed. Check your username and password.")
except paramiko.SSHException as e:
    print(f"SSH error: {str(e)}")
except paramiko.ssh_exception.NoValidConnectionsError:
    print(f"Could not connect to {controller_node}:{port}. Check hostname and port.")
except Exception as e:
    print(f"An error occurred: {str(e)}")
    import traceback
    traceback.print_exc()
finally:
    # Close the connection
    client.close()
