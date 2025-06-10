import paramiko
import sys

# SSH connection details
minio_server = morpheus['server']['hostname']
port = 22
username = sys.argv[1]
password = sys.argv[2]
sudo_password = password  # Using the same password for sudo

# Create SSH client
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())


try:
    # Connect to the remote server
    client.connect(hostname=minio_server, port=port, username=username, password=password)

    # Execute MinIO commands
    
    # 1. Create Bucket
    print("\n--- Creating bucket ---")
    command = "mcli mb minios3/test2"
    stdin, stdout, stderr = client.exec_command(command)
    output = stdout.read().decode().strip()
    error = stderr.read().decode().strip()
    
    if error:
        print(f"Error creating bucket: {error}")
    else:
        print(output if output else "Bucket created successfully")
    
    # 2. Add User
    print("\n--- Adding user ---")
    command = "mcli admin user add minios3 testuser test1234"
    stdin, stdout, stderr = client.exec_command(command)
    output = stdout.read().decode().strip()
    error = stderr.read().decode().strip()
    
    if error:
        print(f"Error adding user: {error}")
    else:
        print(output if output else "User added successfully")
    
    # 3. Assign role to user
    print("\n--- Assigning policy to user ---")
    command = "mcli admin policy attach minios3 readwrite --user testuser"
    stdin, stdout, stderr = client.exec_command(command)
    output = stdout.read().decode().strip()
    error = stderr.read().decode().strip()
    
    if error:
        print(f"Error attaching policy: {error}")
    else:
        print(output if output else "Policy attached successfully")
    
    # 4. Confirm policy attachment
    print("\n--- Confirming policy attachment ---")
    command = "mcli admin user info minios3 testuser"
    stdin, stdout, stderr = client.exec_command(command)
    output = stdout.read().decode().strip()
    error = stderr.read().decode().strip()
    
    if error:
        print(f"Error getting user info: {error}")
    else:
        print(output)
    
    # 5. Create Access Keys
    print("\n--- Creating access keys ---")
    command = "mcli admin accesskey create minios3 testuser --name \"Foobar's Access Key\" --description \"Access key for foobar\""
    stdin, stdout, stderr = client.exec_command(command)
    output = stdout.read().decode().strip()
    error = stderr.read().decode().strip()
    
    if error:
        print(f"Error creating access key: {error}")
    else:
        print(output)

except paramiko.AuthenticationException:
    print("Authentication failed. Check your username and password.")
except paramiko.SSHException as e:
    print(f"SSH error: {str(e)}")
except paramiko.ssh_exception.NoValidConnectionsError:
    print(f"Could not connect to {minio_server}:{port}. Check hostname and port.")
except Exception as e:
    print(f"An error occurred: {str(e)}")
    import traceback
    traceback.print_exc()
finally:
    # Close the connection
    client.close()

