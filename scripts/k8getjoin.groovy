// k8getjoin.groovy
// Morpheus Groovy Script Task. Returns the output of
// `kubeadm token create --print-join-command` from the control plane of this instance.
//
// Workflow: Kubernetes New Cluster Deploy, phase Post Provision, before ubuntu-k8-join-node.
// Task settings: CODE = k8getjoin (the join role reads results.k8getjoin)
//                RESULT TYPE = Single Value, SOURCE = Local
// stdout carries ONLY the join command (nothing on the control plane itself);
// every diagnostic is in the exception message.
//
// Behaviour
//   - Control plane = the instance node whose hostname equals the instance name. It is
//     reached through the IP Morpheus holds for it (instance.containers), not by name,
//     so a stale DNS record on the appliance cannot redirect the connection. If no IP is
//     found, the name is used and the answering host is checked against it.
//   - Authentication: the SSH key Ansible already uses (SSH_KEY_PATH) with `sudo -n`.
//     If that key is unreadable or rejected, the password from Cypher (CYPHER_KEY,
//     read on the caller's token) with `sudo -S`.
//   - Empty in-memory host key store (known_hosts is never read or written).
//   - Waits up to 15 min until the API server answers /readyz; kubeadm init may still
//     be running on the control plane when this task starts.
//   - The bootstrap token lives TOKEN_TTL; it is only needed while this node joins.
//   - An authentication failure stops at once; network errors are retried for 3 min.
//   - The trust-all SSL context is set on the Cypher connection only, not JVM-wide.
//
// Version history
//   <version: set by Emre>  2026-09-17  Groovy port of the Python/paramiko task.
//   <version: set by Emre>  2026-09-17  Connect by IP, key auth first, /readyz check,
//                                       1 h token, scoped SSL trust, fail fast on auth errors.

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import groovy.json.JsonSlurper

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

// ---- Settings ------------------------------------------------------------------------
final String SSH_USER           = "ansible"
final String SSH_KEY_PATH       = "/opt/morpheus/.local/.ssh/id_rsa"
final String CYPHER_KEY         = "password/ansible"
final int    SSH_PORT           = 22
final long   CONNECT_RETRY_MS   = 180000L
final long   RETRY_INTERVAL_MS  = 5000L
final int    CONNECT_TIMEOUT_MS = 10000
final long   COMMAND_TIMEOUT_MS = 60000L
final String ADMIN_CONF         = "/etc/kubernetes/admin.conf"
final long   CONTROLLER_WAIT_MS = 900000L
final long   CONTROLLER_POLL_MS = 15000L
final String TOKEN_TTL          = "1h"

// ---- Helpers ----------------------------------------------------------------------------

class PasswordOnlyUserInfo implements UserInfo, UIKeyboardInteractive {
    private final String pw
    PasswordOnlyUserInfo(String pw) { this.pw = pw }
    String getPassword() { return pw }
    String getPassphrase() { return null }
    boolean promptPassword(String message) { return true }
    boolean promptPassphrase(String message) { return false }
    boolean promptYesNo(String message) { return false }
    void showMessage(String message) { }
    String[] promptKeyboardInteractive(String destination, String name, String instruction,
                                       String[] prompt, boolean[] echo) {
        String[] answers = new String[prompt.length]
        for (int i = 0; i < prompt.length; i++) {
            answers[i] = pw
        }
        return answers
    }
}

SSLSocketFactory trustAllFactory() {
    def trustAll = [ new X509TrustManager() {
        X509Certificate[] getAcceptedIssuers() { return null }
        void checkClientTrusted(X509Certificate[] certs, String authType) {}
        void checkServerTrusted(X509Certificate[] certs, String authType) {}
    } ] as TrustManager[]
    SSLContext sc = SSLContext.getInstance("TLS")
    sc.init(null, trustAll, new java.security.SecureRandom())
    return sc.getSocketFactory()
}

String readCypher(String baseUrl, String token, String key) {
    HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/cypher/" + key).openConnection()
    if (conn instanceof HttpsURLConnection) {
        ((HttpsURLConnection) conn).setSSLSocketFactory(trustAllFactory())
        ((HttpsURLConnection) conn).setHostnameVerifier({ String h, SSLSession s -> true } as HostnameVerifier)
    }
    conn.setRequestMethod("GET")
    conn.setRequestProperty("Authorization", "Bearer " + token)
    conn.setRequestProperty("Accept", "application/json")
    conn.setConnectTimeout(10000)
    conn.setReadTimeout(20000)

    int code
    String body
    try {
        code = conn.getResponseCode()
        InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()
        body = stream != null ? stream.getText("UTF-8") : ""
    } catch (IOException e) {
        throw new RuntimeException("Cypher read '${key}' at ${baseUrl} failed: ${e.getClass().simpleName}: ${e.message}")
    }

    def json = null
    try {
        json = new JsonSlurper().parseText(body)
    } catch (Throwable ignored) {
        json = null
    }

    if (code < 200 || code >= 300 || !(json instanceof Map) || json.success == false) {
        String flat = body.replaceAll(/\s+/, " ").trim()
        String shortBody = flat.length() > 200 ? flat.substring(0, 200) + "..." : flat
        def apiMsg = (json instanceof Map) ? (json.msg ?: json.message ?: json.errors ?: shortBody) : shortBody
        throw new RuntimeException("Cypher read '${key}' failed (HTTP ${code}): ${apiMsg}")
    }

    def data = json.data
    String value = null
    if (data instanceof Map) {
        def v = data.value ?: data.password
        if (v == null && data.size() == 1) {
            v = data.values().first()
        }
        value = v?.toString()
    } else {
        value = data?.toString()
    }
    if (!value) {
        throw new RuntimeException("Cypher key '${key}' returned no value")
    }
    return value
}

String firstNonEmpty(List<Closure> getters) {
    for (Closure g : getters) {
        try {
            def v = g.call()
            if (v != null && v.toString().trim() && v.toString().trim() != "null") {
                return v.toString().trim()
            }
        } catch (Throwable ignored) {
        }
    }
    return null
}

String shortName(String host) {
    return host == null ? "" : host.toLowerCase().tokenize('.').first()
}

// One entry per node of the instance: [hostname, ip]. Works whether the binding holds
// maps (as in Ansible extra vars) or domain objects.
List<Map> instanceNodes(def inst) {
    def list = null
    try {
        list = inst?.containers
    } catch (Throwable ignored) {
        list = null
    }
    List<Map> out = []
    if (list == null) {
        return out
    }
    for (def c : list) {
        def node = c
        String h = firstNonEmpty([{ node.hostname }, { node.server?.hostname }])
        String ip = firstNonEmpty([{ node.internalIp }, { node.server?.internalIp }, { node.externalIp }])
        if (h) {
            out << [hostname: h, ip: ip]
        }
    }
    return out
}

boolean isAuthFailure(Throwable t) {
    String m = (t?.message ?: "").toLowerCase()
    return m.startsWith("auth fail") || m.startsWith("auth cancel") || m.contains("userauth fail") ||
            m.contains("too many authentication failures")
}

Session openSession(String host, int port, String user, String keyPath, String password, int timeoutMs) {
    // No setKnownHosts(): a fresh JSch object has an empty, in-memory host key store,
    // so a rebuilt server's new key is never compared with an old one.
    JSch jsch = new JSch()
    Properties cfg = new Properties()
    cfg.put("StrictHostKeyChecking", "no")
    if (keyPath != null) {
        jsch.addIdentity(keyPath)
        cfg.put("PreferredAuthentications", "publickey")
    } else {
        cfg.put("PreferredAuthentications", "password,keyboard-interactive")
    }
    Session s = jsch.getSession(user, host, port)
    if (password != null) {
        s.setPassword(password)
        s.setUserInfo(new PasswordOnlyUserInfo(password))
    }
    s.setConfig(cfg)
    s.setServerAliveInterval(30000)
    s.connect(timeoutMs)
    return s
}

Session connectWithRetry(String host, int port, String user, String keyPath, String password,
                         int timeoutMs, long retryMs, long intervalMs) {
    long deadline = System.currentTimeMillis() + retryMs
    while (true) {
        try {
            return openSession(host, port, user, keyPath, password, timeoutMs)
        } catch (Throwable t) {
            if (isAuthFailure(t)) {
                throw t
            }
            if (System.currentTimeMillis() + intervalMs >= deadline) {
                throw new RuntimeException("could not connect to ${host}:${port} within " +
                        "${retryMs.intdiv(1000)} s: ${t.getClass().simpleName}: ${t.message}")
            }
            Thread.sleep(intervalMs)
        }
    }
}

// Key first, then the Cypher password. Returns [session, password (null for key auth), auth].
Map connectNode(String host, int port, String user, String keyPath, int timeoutMs,
                long retryMs, long intervalMs, Closure passwordSupplier) {
    List<String> notes = []
    if (keyPath && new File(keyPath).canRead()) {
        try {
            Session s = connectWithRetry(host, port, user, keyPath, null, timeoutMs, retryMs, intervalMs)
            return [session: s, password: null, auth: "key"]
        } catch (Throwable t) {
            if (!isAuthFailure(t)) {
                throw t
            }
            notes << "key ${keyPath} rejected (${t.message})"
        }
    } else {
        notes << "key ${keyPath} not readable by ${System.getProperty('user.name')}"
    }
    String pw
    try {
        pw = passwordSupplier.call()
    } catch (Throwable t) {
        throw new RuntimeException("${notes.join('; ')}; password fallback failed: ${t.message}")
    }
    try {
        Session s = connectWithRetry(host, port, user, null, pw, timeoutMs, retryMs, intervalMs)
        return [session: s, password: pw, auth: "password"]
    } catch (Throwable t) {
        if (isAuthFailure(t)) {
            throw new RuntimeException("SSH authentication as ${user}@${host} failed: " +
                    "${notes.join('; ')}; Cypher password rejected (${t.message})")
        }
        throw t
    }
}

String sudoPrefix(String password) {
    return password != null ? "sudo -S -p '' " : "sudo -n "
}

// Runs a command; a non-null password is written to stdin for sudo -S.
Map runRemote(Session session, String command, String password, long timeoutMs) {
    ChannelExec ch = (ChannelExec) session.openChannel("exec")
    ch.setCommand(command)
    ByteArrayOutputStream out = new ByteArrayOutputStream()
    ByteArrayOutputStream err = new ByteArrayOutputStream()
    ch.setOutputStream(out)
    ch.setErrStream(err)
    OutputStream stdin = ch.getOutputStream()   // must be obtained before connect()
    try {
        ch.connect(10000)
        if (password != null) {
            stdin.write((password + "\n").getBytes("UTF-8"))
            stdin.flush()
        }
        stdin.close()                           // EOF, so sudo stops reading

        long deadline = System.currentTimeMillis() + timeoutMs
        while (!ch.isClosed()) {
            if (System.currentTimeMillis() > deadline) {
                throw new RuntimeException("remote command did not finish within ${timeoutMs.intdiv(1000)} s")
            }
            Thread.sleep(200)
        }
        return [rc: ch.getExitStatus(),
                out: out.toString("UTF-8").trim(),
                err: err.toString("UTF-8").trim()]
    } finally {
        ch.disconnect()
    }
}

// ---- Main -------------------------------------------------------------------------------
try {
    def inst = [
        { instance },
        { morpheus.instance },
        { morpheus['instance'] },
    ].findResult { c ->
        try {
            return c()
        } catch (Throwable t) {
            return null
        }
    }
    String controllerName = firstNonEmpty([{ inst?.name }])
    if (!controllerName) {
        throw new RuntimeException("instance name not found in task bindings; run k8getjoin_probe.groovy on this appliance")
    }

    // The control plane does not need a join command: the join role skips it.
    String workerHost = firstNonEmpty([{ server?.hostname }, { morpheus.server?.hostname }, { morpheus['server']?.hostname }])
    if (workerHost && shortName(workerHost) == shortName(controllerName)) {
        println ""
        return
    }

    Map controller = instanceNodes(inst).find { shortName(it.hostname) == shortName(controllerName) }
    String connectHost = controller?.ip ?: controllerName
    String via = controller?.ip ? "IP ${controller.ip} recorded by Morpheus" : "name (no IP in instance.containers)"

    String applianceUrl = firstNonEmpty([{ morpheus.applianceUrl }])?.replaceAll('/+$', '')
    String apiToken = firstNonEmpty([{ morpheus.apiAccessToken }])
    Closure passwordSupplier = {
        if (!applianceUrl) {
            throw new RuntimeException("morpheus.applianceUrl is empty, cannot read Cypher")
        }
        if (!apiToken) {
            throw new RuntimeException("morpheus.apiAccessToken is empty, cannot read Cypher")
        }
        return readCypher(applianceUrl, apiToken, CYPHER_KEY)
    }

    Map conn = connectNode(connectHost, SSH_PORT, SSH_USER, SSH_KEY_PATH, CONNECT_TIMEOUT_MS,
            CONNECT_RETRY_MS, RETRY_INTERVAL_MS, passwordSupplier)
    Session session = (Session) conn.session
    String pw = (String) conn.password
    if (pw == null) {
        // Key login worked; if sudo still asks for a password, take it from Cypher.
        Map sudoProbe = runRemote(session, "sudo -n true", null, COMMAND_TIMEOUT_MS)
        if (sudoProbe.rc != 0) {
            try {
                pw = passwordSupplier.call()
            } catch (Throwable t) {
                session.disconnect()
                throw new RuntimeException("sudo -n failed on ${connectHost} (${sudoProbe.err ?: 'no output'}) " +
                        "and the password fallback failed: ${t.message}")
            }
        }
    }
    String sudo = sudoPrefix(pw)

    String joinCommand
    try {
        String checkCmd = sudo + "sh -c 'hostname; kubectl --kubeconfig " + ADMIN_CONF +
                " get --raw=/readyz >/dev/null 2>&1'"
        String tokenCmd = sudo + "kubeadm token create --print-join-command --ttl " + TOKEN_TTL +
                " --kubeconfig " + ADMIN_CONF
        String remoteHost = connectHost
        long waitDeadline = System.currentTimeMillis() + CONTROLLER_WAIT_MS
        while (true) {
            Map chk = runRemote(session, checkCmd, pw, COMMAND_TIMEOUT_MS)
            remoteHost = chk.out ? chk.out.readLines().first().trim() : connectHost
            if (chk.out && shortName(remoteHost) != shortName(controllerName)) {
                String resolved
                try {
                    resolved = InetAddress.getAllByName(connectHost)*.hostAddress.join(", ")
                } catch (Throwable t) {
                    resolved = "unresolvable: " + t.message
                }
                throw new RuntimeException("connected to ${controllerName} by ${via} [${resolved}], " +
                        "but that address answered as ${remoteHost}. The address Morpheus or DNS holds " +
                        "for ${controllerName} is stale.")
            }
            if (chk.rc == 0) {
                break
            }
            // rc!=0 with a hostname on stdout: sudo worked, the API server is not ready yet.
            // rc!=0 without output: sudo failed (no sudo right, wrong password). Fail at once.
            if (!chk.out) {
                throw new RuntimeException("sudo failed on ${connectHost} (${conn.auth} auth, rc=${chk.rc}): " +
                        "${chk.err ?: 'no output'}")
            }
            if (System.currentTimeMillis() >= waitDeadline) {
                throw new RuntimeException("API server on ${remoteHost} (connected by ${via}) did not answer " +
                        "/readyz within ${CONTROLLER_WAIT_MS.intdiv(1000)} s. Either kubeadm init has not " +
                        "completed or failed there, or ${ADMIN_CONF} is missing.")
            }
            Thread.sleep(CONTROLLER_POLL_MS)
        }

        Map res = runRemote(session, tokenCmd, pw, COMMAND_TIMEOUT_MS)
        if (res.rc != 0 || !res.out.startsWith("kubeadm join")) {
            String detail = res.err ?: (res.out ?: "no output")
            throw new RuntimeException("kubeadm token create failed on ${remoteHost} (rc=${res.rc}): ${detail}")
        }
        joinCommand = res.out
    } finally {
        session.disconnect()
    }

    println joinCommand
} catch (Throwable t) {
    throw new RuntimeException("k8getjoin: " + (t.message ?: t.getClass().name), t)
}
