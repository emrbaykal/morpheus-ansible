// k8getjoin.groovy
// Morpheus Groovy Script Task. Returns `kubeadm token create --print-join-command` from the
// control plane of this instance, so the next task (ubuntu-k8-join-node.yml) can join this node.
//
// Task settings: CODE = k8getjoin, RESULT TYPE = Single Value, SOURCE = Local.
// stdout is only the join command, and empty on the control plane itself.
//
// The control plane is the instance node whose hostname equals the instance name; it is
// reached by the IP Morpheus holds for it, so appliance DNS is not involved. Authentication
// is the SSH key Ansible uses, or the Cypher password if that key is rejected.
//
// Version history
//   <version: set by Emre>  2026-09-17  Groovy port of get-join-command.py.

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
final String TOKEN_TTL        = "1h"
final long   CONNECT_RETRY_MS = 180000L
final long   READY_WAIT_MS    = 900000L
final long   POLL_MS          = 15000L

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

// The Groovy task binding is not a Map: `morpheus` is a com.morpheus.MorpheusAccess object
// that exposes applianceUrl and apiAccessToken, while `instance` and `server` are top-level
// bindings. The source list keeps the script portable across Morpheus versions.
def bindingValue(List<Closure> sources) {
    for (Closure c : sources) {
        try {
            def v = c.call()
            if (v != null) {
                return v
            }
        } catch (Throwable ignored) {
        }
    }
    return null
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
            // OpenSSH 8.8+ refuses RSA keys signed with SHA-1 (ssh-rsa). Ask for SHA-2
            // explicitly; both spellings are set because the key name differs between JSch
            // versions, and an unknown key is ignored.
            s.setConfig("PubkeyAcceptedAlgorithms", "rsa-sha2-512,rsa-sha2-256,ssh-ed25519,ecdsa-sha2-nistp256,ssh-rsa")
            s.setConfig("PubkeyAcceptedKeyTypes", "rsa-sha2-512,rsa-sha2-256,ssh-ed25519,ecdsa-sha2-nistp256,ssh-rsa")
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
String controllerIp(def inst) {
    String name = inst?.name
    def node = inst?.containers?.find { it.hostname == name }
    if (!node?.internalIp) {
        throw new RuntimeException("no IP for control plane '${name}' in instance.containers")
    }
    return node.internalIp
}

// ---- Main ------------------------------------------------------------------------------
try {
    def inst = bindingValue([{ instance }, { morpheus['instance'] }])
    if (!inst?.name) {
        throw new RuntimeException("no 'instance' binding in this task context; bindings are " +
                binding.variables.keySet())
    }
    def srv = bindingValue([{ server }, { morpheus['server'] }])

    // The control plane needs no join command: the join role skips it.
    if (srv?.hostname == inst.name) {
        println ""
        return
    }

    String host = controllerIp(inst)
    // Key first, Cypher password second. Every step is recorded so one failed run says which
    // credential was refused, instead of a single "Auth fail".
    String pw = null
    Session session = null
    List<String> notes = []
    File key = new File(SSH_KEY_PATH)
    if (key.canRead()) {
        try {
            session = connect(host, SSH_USER, SSH_KEY_PATH, null, CONNECT_RETRY_MS)
        } catch (Throwable t) {
            if (!isAuthFailure(t)) {
                throw t
            }
            notes << "key ${SSH_KEY_PATH} refused by ${host}"
        }
    } else {
        notes << "key ${SSH_KEY_PATH} not readable by JVM user ${System.getProperty('user.name')} (exists=${key.exists()})"
    }
    if (session == null) {
        try {
            pw = readCypher(morpheus.applianceUrl.toString().replaceAll('/+$', ''), morpheus.apiAccessToken, CYPHER_KEY)
        } catch (Throwable t) {
            throw new RuntimeException("${notes.join('; ')}; Cypher ${CYPHER_KEY}: ${t.message}")
        }
        try {
            session = connect(host, SSH_USER, null, pw, CONNECT_RETRY_MS)
        } catch (Throwable t) {
            throw new RuntimeException("${notes.join('; ')}; password from Cypher ${CYPHER_KEY} refused: ${t.message}")
        }
    }
    String sudo = pw ? "sudo -S -p '' " : "sudo -n "

    String joinCommand
    try {
        // kubeadm writes admin.conf before the API server is up, so wait for /readyz itself.
        long deadline = System.currentTimeMillis() + READY_WAIT_MS
        while (true) {
            Map chk = run(session, sudo + "sh -c 'hostname; kubectl --kubeconfig " + ADMIN_CONF +
                    " get --raw=/readyz >/dev/null 2>&1'", pw)
            if (chk.rc == 0) {
                break
            }
            if (!chk.out) {
                throw new RuntimeException("sudo failed on ${host}: ${chk.err ?: 'no output'}")
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new RuntimeException("${chk.out} (${host}) did not answer /readyz within " +
                        "${READY_WAIT_MS.intdiv(1000)} s; kubeadm init has not finished there")
            }
            Thread.sleep(POLL_MS)
        }

        Map res = run(session, sudo + "kubeadm token create --print-join-command --ttl " + TOKEN_TTL +
                " --kubeconfig " + ADMIN_CONF, pw)
        if (res.rc != 0) {
            throw new RuntimeException("kubeadm token create failed on ${host}: ${res.err ?: res.out}")
        }
        joinCommand = res.out
    } finally {
        session.disconnect()
    }

    println joinCommand
} catch (Throwable t) {
    throw new RuntimeException("k8getjoin: " + (t.message ?: t.getClass().name), t)
}
