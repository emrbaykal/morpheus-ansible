import time
import os
import subprocess
import json

# 1. Wait for 120 seconds
print("Waiting 120 seconds for InnoDB Cluster to build...")
time.sleep(120)

# 2. Create MySQL config file
mysql_config = """
[client]
user={user}
password={password}
host={host}
""".format(
    user=os.environ.get("INNODB_ADMIN_USER", "admin"),
    password=os.environ.get("INNODB_ADMIN_PASSWORD", "password"),
    host=os.uname()[1]
)
with open("/tmp/.mylogin.cnf", "w") as f:
    f.write(mysql_config)
os.chmod("/tmp/.mylogin.cnf", 0o600)

# 3. Create MySQL Shell JS script
# You can get the required variables from the environment or set them statically
cluster_admin = os.environ.get("INNODB_ADMIN_USER", "admin")
cluster_password = os.environ.get("INNODB_ADMIN_PASSWORD", "password")
cluster_name = os.environ.get("INNODB_CLS_NAME", "myCluster")
db_hosts = json.loads(os.environ.get("INNODB_DB_HOSTS", '["localhost"]'))
host = os.uname()[1]

js_script = f"""
// MySQL Shell script to check and configure instance
var host = "{host}";
var clusterAdmin = "{cluster_admin}";
var password = "{cluster_password}";
var dbHosts = {json.dumps(db_hosts)};
var clusterName = "{cluster_name}";

function sleep(milliseconds) {{
    const date = Date.now();
    let currentDate = null;
    do {{
        currentDate = Date.now();
    }} while (currentDate - date < milliseconds);
}}
print('\\nNumber of Hosts: ' + dbHosts.length );
print('\\nList of hosts:\\n');

for (var i = 0; i < dbHosts.length; i++) {{
    print("Host " + i + ": " + dbHosts[i] + "\\n");
}}

function setupCluster() {{
    print('\\nConfiguring the instances.');
    for (var n = 0; n < dbHosts.length; n++) {{
        print('\\n=> ');
        shell.connect({{ host: dbHosts[n], user: clusterAdmin, password: password }});
        print("Connected" + dbHosts[n] + "successfully!" + "\\n");
        print("Checking instance configuration...");
        var result = dba.checkInstanceConfiguration();
        if (result.status == "ok") {{
            print("Instance is already configured on " + dbHosts[n] + " for InnoDB Cluster usage.");
        }} else {{
            print("\\nConfiguring instance...");
            var config_result = dba.configureInstance({{host: dbHosts[n], user: clusterAdmin, password: password}}, {{restart: true}})
            print("Instance configuration result: " + JSON.stringify(config_result, null, 2));
        }}
    }}
    print('\\nConfiguring Instances completed.\\n\\n');
    sleep(5000);
    print("Connecting to primary node: " + host);
    shell.connect({{ host: host, user: clusterAdmin, password: password }});
    try {{
        print("Setting up InnoDB Cluster on " + host + ".\\n\\n");
        var cluster = dba.createCluster(clusterName);
        print("Instance configuration result: " + JSON.stringify(cluster, null, 2));
    }} catch (e) {{
        print('\\nThe InnoDB cluster could not be created.\\n');
        print(e + '\\n');
    }}
    print('Adding instances to the cluster.\\n');
    cluster = dba.getCluster();
    for (var i = 0; i < dbHosts.length; i++) {{
        print('\\n=> ');
        if (dbHosts[i] === host) {{
            print("Skipping primary node " + host);
            continue;
        }}
        try {{
            print("Adding instance " + dbHosts[i] + " to the cluster." + "\\n");
            add_cluster = cluster.addInstance(dbHosts[i], {{recoveryMethod: 'clone'}});
            print("\\nInstance " + dbHosts[i] + " successfully added to the cluster.");
        }} catch (e) {{
            print('\\nThe Instabce could not be addet to the cluster.\\n');
            print(e + '\\n');
        }}
    }}
}}
setupCluster();
"""

with open("/tmp/configure_instance.js", "w") as f:
    f.write(js_script)
os.chmod("/tmp/configure_instance.js", 0o600)

# 4. MySQL Shell komutunu çalıştır
cmd = [
    "mysqlsh",
    "--defaults-file=/tmp/.mylogin.cnf",
    "--no-wizard",
    "--js",
    "--file=/tmp/configure_instance.js"
]
print("Running MySQL Shell script...")
result = subprocess.run(cmd, capture_output=True, text=True)
if result.returncode != 0:
    print("MySQL Shell failed!")
    print(result.stderr)
    exit(result.returncode)
else:
    print("MySQL Shell output:")
    print(result.stdout)

# 5. Geçici dosyaları sil
os.remove("/tmp/configure_instance.js")
os.remove("/tmp/.mylogin.cnf")
print("Temporary files cleaned up.")