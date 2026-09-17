// k8labelworker.groovy
// Morpheus Groovy Script Task. Labels this node as a Kubernetes worker
// (node-role.kubernetes.io/worker=worker) with kubectl on the control plane.
//
// Task settings: CODE = k8labelworker, SOURCE = Local, no result type needed.
// Runs after ubuntu-k8-join-node.yml and does nothing on the control plane.
// Connection and authentication work exactly as in k8getjoin.groovy.
//
// Version history
//   <version: set by Emre>  2026-09-17  Groovy port of label-k8-command.py.

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import groovy.json.JsonSlurper

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

// ---- Settings --------------------------------------------------------------------------
final String SSH_USER         = "ansible"
final String SSH_KEY_PATH     = "/opt/morpheus/.local/.ssh/id_rsa"
final String CYPHER_KEY       = "password/ansible"
final String ADMIN_CONF       = "/etc/kubernetes/admin.conf"
final String LABEL            = "node-role.kubernetes.io/worker=worker"
final long   CONNECT_RETRY_MS = 180000L
final long   NODE_WAIT_MS     = 300000L
final long   NODE_POLL_MS     = 10000L

// ---- Helpers ---------------------------------------------------------------------------

// Cypher over REST on the caller's token. The appliance certificate is self-signed, so the
// trust-all context is set on this connection only, never JVM-wide.
String readCypher(String baseUrl, String token, String key) {
    def trustAll = [ new X509TrustManager() {
        X509Certificate[] getAcceptedIssuers() { return null }
        void checkClientTrusted(X509Certificate[] c, String a) {}
        void checkServerTrusted(X509Certificate[] c, String a) {}
    } ] as TrustManager[]
    SSLContext sc = SSLContext.getInstance("TLS")
    sc.init(null, trustAll, new java.security.SecureRandom())

    HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/cypher/" + key).openConnection()
    if (conn instanceof HttpsURLConnection) {
        conn.setSSLSocketFactory(sc.getSocketFactory())
        conn.setHostnameVerifier({ String h, SSLSession s -> true } as HostnameVerifier)
    }
    conn.setRequestProperty("Authorization", "Bearer " + token)
    conn.setConnectTimeout(10000)
    conn.setReadTimeout(20000)

    int code = conn.getResponseCode()
    if (code != 200) {
        throw new RuntimeException("Cypher read '${key}' failed (HTTP ${code})")
    }
    String value = new JsonSlurper().parseText(conn.getInputStream().getText("UTF-8"))?.data?.value
    if (!value) {
        throw new RuntimeException("Cypher key '${key}' returned no value")
    }
    return value
}

boolean isAuthFailure(Throwable t) {
    String m = (t?.message ?: "").toLowerCase()
    return m.contains("auth fail") || m.contains("auth cancel") || m.contains("too many authentication")
}

// No setKnownHosts(): the in-memory host key store is empty, so a rebuilt node is never
// compared with an old key. Network errors are retried, an authentication failure is not.
Session connect(String host, String user, String keyPath, String password, long retryMs) {
    long deadline = System.currentTimeMillis() + retryMs
    while (true) {
        try {
            JSch jsch = new JSch()
            if (keyPath) {
                jsch.addIdentity(keyPath)
            }
            Session s = jsch.getSession(user, host, 22)
            if (password) {
                s.setPassword(password)
            }
            s.setConfig("StrictHostKeyChecking", "no")
            s.setConfig("PreferredAuthentications", keyPath ? "publickey" : "password")
            s.connect(10000)
            return s
        } catch (Throwable t) {
            if (isAuthFailure(t)) {
                throw new RuntimeException("SSH ${user}@${host}: ${t.message}")
            }
            if (System.currentTimeMillis() + 5000L >= deadline) {
                throw t
            }
            Thread.sleep(5000L)
        }
    }
}

// Runs one command. A non-null password is written to stdin for sudo -S.
Map run(Session session, String command, String password) {
    ChannelExec ch = (ChannelExec) session.openChannel("exec")
    ch.setCommand(command)
    ByteArrayOutputStream out = new ByteArrayOutputStream()
    ByteArrayOutputStream err = new ByteArrayOutputStream()
    ch.setOutputStream(out)
    ch.setErrStream(err)
    OutputStream stdin = ch.getOutputStream()   // must be obtained before connect()
    try {
        ch.connect(10000)
        if (password) {
            stdin.write((password + "\n").getBytes("UTF-8"))
            stdin.flush()
        }
        stdin.close()
        while (!ch.isClosed()) {
            Thread.sleep(200)
        }
        return [rc: ch.getExitStatus(), out: out.toString("UTF-8").trim(), err: err.toString("UTF-8").trim()]
    } finally {
        ch.disconnect()
    }
}

// The node of the instance whose hostname equals the instance name, by IP.
String controllerIp(def instance) {
    String name = instance?.name
    def node = instance?.containers?.find { it.hostname == name }
    if (!node?.internalIp) {
        throw new RuntimeException("no IP for control plane '${name}' in instance.containers")
    }
    return node.internalIp
}

// ---- Main ------------------------------------------------------------------------------
try {
    def instance = morpheus.instance
    String workerHost = morpheus.server?.hostname
    if (!workerHost || workerHost == instance?.name) {
        println "k8labelworker: nothing to do on ${workerHost ?: 'this node'}"
        return
    }

    // kubeadm registers the node under its lower-case hostname. Checked because the name
    // goes into a shell command on the control plane.
    String nodeName = workerHost.toLowerCase()
    if (!(nodeName ==~ /[a-z0-9]([-a-z0-9.]*[a-z0-9])?/)) {
        throw new RuntimeException("'${workerHost}' is not a valid Kubernetes node name")
    }

    String host = controllerIp(instance)
    String pw = null
    Session session = null
    if (new File(SSH_KEY_PATH).canRead()) {
        try {
            session = connect(host, SSH_USER, SSH_KEY_PATH, null, CONNECT_RETRY_MS)
        } catch (Throwable t) {
            if (!isAuthFailure(t)) {
                throw t
            }
        }
    }
    if (session == null) {
        pw = readCypher(morpheus.applianceUrl.toString().replaceAll('/+$', ''), morpheus.apiAccessToken, CYPHER_KEY)
        session = connect(host, SSH_USER, null, pw, CONNECT_RETRY_MS)
    }
    String kubectl = (pw ? "sudo -S -p '' " : "sudo -n ") + "kubectl --kubeconfig " + ADMIN_CONF + " "

    String result
    try {
        long deadline = System.currentTimeMillis() + NODE_WAIT_MS
        while (run(session, kubectl + "get node " + nodeName + " -o name", pw).rc != 0) {
            if (System.currentTimeMillis() >= deadline) {
                throw new RuntimeException("node ${nodeName} did not register within " +
                        "${NODE_WAIT_MS.intdiv(1000)} s")
            }
            Thread.sleep(NODE_POLL_MS)
        }

        // --overwrite so a re-run of the task succeeds.
        Map res = run(session, kubectl + "label node " + nodeName + " " + LABEL + " --overwrite", pw)
        if (res.rc != 0) {
            throw new RuntimeException("kubectl label failed on ${host}: ${res.err ?: res.out}")
        }
        result = res.out
    } finally {
        session.disconnect()
    }

    println "k8labelworker: ${result}"
} catch (Throwable t) {
    throw new RuntimeException("k8labelworker: " + (t.message ?: t.getClass().name), t)
}
