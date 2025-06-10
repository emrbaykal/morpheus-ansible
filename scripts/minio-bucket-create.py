import paramiko
import sys
import json
import os

# SSH connection details
minio_server = morpheus['server']['hostname']
bucked_name = morpheus['customOptions']['bucked_name']
bucked_accesskey =  morpheus['customOptions']['bucked_accesskey']
bucked_secretkey = morpheus['customOptions']['bucked_secretkey']
bucked_policy = morpheus['customOptions']['bucked_policy']
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
    command = f"echo '{sudo_password}' | sudo -S mcli mb minios3/{bucked_name}"
    stdin, stdout, stderr = client.exec_command(command)
    exit_status = stdout.channel.recv_exit_status()
    output = stdout.read().decode().strip()
    error = stderr.read().decode().strip()
    
    if exit_status != 0:
        print(f"Error creating bucket: {error}")
        print(f"Command exit status: {exit_status}")
        print(f"Command output: {output}")
        sys.exit(1) 
    else:
        print(output if output else "Bucket created successfully")
       
    
     # 2. Add User
    print("\n--- Adding user ---")
    command = f"echo '{sudo_password}' | sudo -S mcli admin user add minios3 {bucked_accesskey} {bucked_secretkey}"
    stdin, stdout, stderr = client.exec_command(command)
    exit_status = stdout.channel.recv_exit_status()
    output = stdout.read().decode().strip()
    error = stderr.read().decode().strip()

    if exit_status != 0:
        print(f"Error adding user: {error}")
        print(f"Command exit status: {exit_status}")
        print(f"Command output: {output}")
        sys.exit(1) 
    else:
        print(output if output else "User added successfully")
        
    # 3. Assign role to user
    print("\n--- Assigning policy to user ---")
    command = f"echo '{sudo_password}' | sudo -S mcli admin policy attach minios3 {bucked_policy} --user {bucked_accesskey}"
    stdin, stdout, stderr = client.exec_command(command)
    output = stdout.read().decode().strip()
    error = stderr.read().decode().strip()
    
    if exit_status != 0:
        print(f"Error attaching policy: {error}")
        print(f"Command exit status: {exit_status}")
        print(f"Command output: {output}")
        sys.exit(1) 
    else:
        print(output if output else "Policy attached successfully")
    
    # 4. Confirm policy attachment
    print("\n--- Confirming policy attachment ---")
    command = f"echo '{sudo_password}' | sudo -S mcli admin user info minios3 {bucked_accesskey}"
    stdin, stdout, stderr = client.exec_command(command)
    output = stdout.read().decode().strip()
    error = stderr.read().decode().strip()
    
    if exit_status != 0:
        print(f"Error getting user info: {error}")
        print(f"Command exit status: {exit_status}")
        print(f"Command output: {output}")
        sys.exit(1)
    else:
        print(output)
    
    # 5. Create Access Keys
    print("\n--- Creating access keys ---")
    command = f"echo '{sudo_password}' | sudo -S mcli admin accesskey create minios3 {bucked_accesskey} --name \"{bucked_accesskey}'s Access Key\" --description \"Access key for {bucked_accesskey}\""
    stdin, stdout, stderr = client.exec_command(command)
    output = stdout.read().decode().strip()
    error = stderr.read().decode().strip()
    
    if exit_status != 0:
        print(f"Error creating access key: {error}")
        print(f"Command exit status: {exit_status}")
        print(f"Command output: {output}")
        sys.exit(1)
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

