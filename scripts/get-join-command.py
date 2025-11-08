import paramiko
import sys
import time

# SSH connection details
hostname = morpheus['instance']['name']
port = 22
username = sys.argv[1]
password = sys.argv[2]
sudo_password = password  # Using the same password for sudo

# Create SSH client
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())

# Connection retry settings
max_retry_time = 180  # 3 minutes in seconds
retry_interval = 5    # 5 seconds between retries
start_time = time.time()

try:
    # Connect to the remote server with retry logic
    connected = False
    while not connected and (time.time() - start_time) < max_retry_time:
        try:
            client.connect(hostname=hostname, port=port, username=username, password=password, timeout=10)
            connected = True
        except (paramiko.AuthenticationException, paramiko.SSHException, paramiko.ssh_exception.NoValidConnectionsError, Exception) as e:
            elapsed_time = time.time() - start_time
            if elapsed_time >= max_retry_time:
                raise e
            time.sleep(retry_interval)
    
    if not connected:
        raise Exception("Failed to connect within 3 minutes")

    # Using sudo -S to read password from stdin
    command = f"echo '{sudo_password}' | sudo -S kubeadm token create --print-join-command"
    stdin, stdout, stderr = client.exec_command(command)

    # Check for errors
    error = stderr.read().decode().strip()
    if error and not error.startswith("[sudo]"):  # Ignore the sudo password prompt in stderr
        print(f"Command returned error: {error}")

    # Get the output
    join_command = stdout.read().decode().strip()

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
    client.close()
