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

# Check if hostname not matches instance name
if controller_node != worker_node:
    try:
        # Connect to the remote server
        client.connect(hostname=controller_node, port=port, username=username, password=password)

        # Using sudo -S to read password from stdin
        command = f"echo '{sudo_password}' | sudo -S kubectl label node {worker_node} node-role.kubernetes.io/worker=worker"
        stdin, stdout, stderr = client.exec_command(command)

        # Check for errors
        error = stderr.read().decode().strip()
        if error and not error.startswith("[sudo]"):  # Ignore the sudo password prompt in stderr
            print(f"Command returned error: {error}")

        # Get the output
        label_command = stdout.read().decode().strip()

        if label_command:
            print(label_command)
        else:
            print("WARNING: Join command is empty, command might have failed")

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
else:
    print(f"Kubernetes Controller Node , no action needed.")
