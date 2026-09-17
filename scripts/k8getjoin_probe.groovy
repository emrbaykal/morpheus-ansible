// k8getjoin_probe.groovy
// One-shot diagnostic. Run once as a Groovy Script Task in the same workflow context as
// k8getjoin.groovy and k8labelworker.groovy (Post Provision phase of a multi-node order).
// Prints which SSH libraries the appliance JVM exposes, which bindings carry the target host,
// how instance.containers looks from Groovy, and whether the Ansible SSH key is usable.
// Only selected fields are printed: container objects can carry cloudConfig (user-data).
//
// Version history
//   <version: set by Emre>  2026-09-17  First version.
//   <version: set by Emre>  2026-09-17  Added instance.containers and SSH key checks.

def candidates = [
    "com.jcraft.jsch.JSch",
    "net.schmizz.sshj.SSHClient",
    "org.apache.sshd.client.SshClient",
    "com.trilead.ssh2.Connection",
]
def loaders = [this.class.classLoader, Thread.currentThread().contextClassLoader].findAll { it != null }.unique()

println "---- SSH libraries"
candidates.each { cn ->
    def found = loaders.findResult { cl ->
        try {
            return Class.forName(cn, false, cl)
        } catch (Throwable t) {
            return null
        }
    }
    if (found) {
        def ver = "unknown"
        def jar = "unknown"
        try { ver = found.getPackage()?.getImplementationVersion() ?: ver } catch (Throwable t) { }
        try { ver = found.getField("VERSION").get(null)?.toString() ?: ver } catch (Throwable t) { }
        try { jar = found.getProtectionDomain()?.getCodeSource()?.getLocation()?.toString() ?: jar } catch (Throwable t) { }
        println "FOUND   ${cn}  version=${ver}  jar=${jar}"
    } else {
        println "MISSING ${cn}"
    }
}

println "---- bindings"
binding.variables.each { k, v -> println "${k} (${v?.getClass()?.name})" }

println "---- target host candidates"
[
    "instance?.name"          : { instance?.name },
    "morpheus.instance?.name" : { morpheus.instance?.name },
    "server?.name"            : { server?.name },
    "server?.hostname"        : { server?.hostname },
    "server?.internalIp"      : { server?.internalIp },
    "server?.externalIp"      : { server?.externalIp },
].each { label, c ->
    def v
    try {
        v = c()
    } catch (Throwable t) {
        v = "<" + t.getClass().simpleName + ">"
    }
    println "${label} = ${v}"
}

println "---- instance.containers"
def inst = [{ instance }, { morpheus.instance }, { morpheus['instance'] }].findResult { c ->
    try { return c() } catch (Throwable t) { return null }
}
println "instance type = ${inst?.getClass()?.name}"
def list = null
try {
    list = inst?.containers
    println "containers type = ${list?.getClass()?.name}, size = ${list?.size()}"
} catch (Throwable t) {
    println "containers = <${t.getClass().simpleName}: ${t.message}>"
}
int idx = 0
list?.each { c ->
    def fields = [
        "type"              : { c?.getClass()?.name },
        "hostname"          : { c.hostname },
        "internalIp"        : { c.internalIp },
        "externalIp"        : { c.externalIp },
        "server.hostname"   : { c.server?.hostname },
        "server.internalIp" : { c.server?.internalIp },
    ]
    String line = fields.collect { k, g ->
        def v
        try { v = g() } catch (Throwable t) { v = "<" + t.getClass().simpleName + ">" }
        "${k}=${v}"
    }.join("  ")
    println "[${idx}] ${line}"
    idx++
}

println "---- SSH key for key authentication"
def keyFile = new File("/opt/morpheus/.local/.ssh/id_rsa")
println "jvm user = ${System.getProperty('user.name')}"
println "key exists = ${keyFile.exists()}, readable = ${keyFile.canRead()}"
if (keyFile.canRead()) {
    def first = keyFile.withReader { it.readLine() }
    println "key header = ${first}"
}
