// rustfs_register_bucket.groovy
// Morpheus Groovy Script Task. Registers a RustFS bucket in Morpheus as an S3 Storage
// Bucket, so it can be browsed under Infrastructure > Storage > Buckets and used as a
// backup, deployment or virtual image target.
//
// Task settings: CODE = rustfs_register_bucket, SOURCE = Local, no result type needed.
// Workflow: the same operational workflow as the bucket creation, second task, target = the
// RustFS instance. It reuses the form inputs of the first task:
//   rustfs_bucket_name, rustfs_bucket_access_key, rustfs_bucket_secret_key,
//   rustfs_bucket_policy.
//
// The bucket credentials come from the form, so nothing has to be read back from the
// instance config. The Morpheus API is called with the token of the user running the
// workflow, so the bucket is created under their account and their permissions apply.
//
// Version history
//   <version: set by Emre>  2026-09-18  First version.

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

// ---- Settings --------------------------------------------------------------------------
final String REGION  = "us-east-1"
final int    TIMEOUT = 20000

// ---- Helpers ---------------------------------------------------------------------------

// One call to the Morpheus API on the caller's token. The appliance certificate is
// self-signed, so the trust-all context is set on this connection only, never JVM-wide.
Map api(String baseUrl, String token, String method, String path, String body, int timeout) {
    def trustAll = [ new X509TrustManager() {
        X509Certificate[] getAcceptedIssuers() { return null }
        void checkClientTrusted(X509Certificate[] c, String a) {}
        void checkServerTrusted(X509Certificate[] c, String a) {}
    } ] as TrustManager[]
    SSLContext sc = SSLContext.getInstance("TLS")
    sc.init(null, trustAll, new java.security.SecureRandom())

    HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection()
    if (conn instanceof HttpsURLConnection) {
        conn.setSSLSocketFactory(sc.getSocketFactory())
        conn.setHostnameVerifier({ String h, SSLSession s -> true } as HostnameVerifier)
    }
    conn.setRequestMethod(method)
    conn.setRequestProperty("Authorization", "Bearer " + token)
    conn.setRequestProperty("Accept", "application/json")
    conn.setConnectTimeout(timeout)
    conn.setReadTimeout(timeout)
    if (body) {
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setDoOutput(true)
        conn.getOutputStream().withStream { it.write(body.getBytes("UTF-8")) }
    }

    int code = conn.getResponseCode()
    InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()
    String text = stream != null ? stream.getText("UTF-8").trim() : ""
    def json = null
    try {
        json = new JsonSlurper().parseText(text)
    } catch (Throwable ignored) {
        json = null
    }
    return [code: code, body: text, json: json]
}

// Morpheus answers 200 with success:false on some failures, so both are checked.
String apiError(Map res) {
    def j = res.json
    def msg = (j instanceof Map) ? (j.msg ?: j.message ?: j.errors) : null
    String flat = (msg ?: res.body).toString().replaceAll(/\s+/, " ").trim()
    return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat
}

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

// ---- Main ------------------------------------------------------------------------------
try {
    def inst = bindingValue([{ instance }, { morpheus['instance'] }])
    def formOpts = bindingValue([{ customOptions }, { morpheus['customOptions'] }]) ?: [:]
    if (!inst?.name) {
        throw new RuntimeException("no 'instance' binding; run this task against a RustFS instance. " +
                "Bindings are " + binding.variables.keySet())
    }

    String bucket = formOpts.rustfs_bucket_name?.toString()?.trim()
    String bucketKey = formOpts.rustfs_bucket_access_key?.toString()?.trim()
    String bucketSecret = formOpts.rustfs_bucket_secret_key?.toString()?.trim()
    String access = (formOpts.rustfs_bucket_policy ?: "read-write").toString().trim().toLowerCase()
    if (!bucket || !bucketKey || !bucketSecret) {
        throw new RuntimeException("rustfs_bucket_name, rustfs_bucket_access_key and " +
                "rustfs_bucket_secret_key are required form inputs")
    }

    String applianceUrl = (morpheus.applianceUrl ?: "").toString().replaceAll('/+$', '')
    String apiToken = (morpheus.apiAccessToken ?: "").toString()
    if (!applianceUrl || !apiToken) {
        throw new RuntimeException("morpheus.applianceUrl or morpheus.apiAccessToken is empty")
    }

    // The S3 port is not a secret, so it can be read back from the instance config.
    Map instRes = api(applianceUrl, apiToken, "GET", "/api/instances/" + inst.id, null, TIMEOUT)
    if (instRes.code != 200) {
        throw new RuntimeException("GET /api/instances/${inst.id} returned HTTP ${instRes.code}: ${apiError(instRes)}")
    }
    def instOpts = instRes.json?.instance?.config?.customOptions ?: [:]
    String port = (instOpts.rustfs_s3_api_port ?: "9000").toString().trim()

    def node = inst.containers?.find { it.hostname == inst.name } ?: inst.containers?.getAt(0)
    if (!node?.internalIp) {
        throw new RuntimeException("no IP for the RustFS server in instance.containers")
    }
    String endpoint = "http://${node.internalIp}:${port}"
    String bucketLabel = "${inst.name}-${bucket}"

    // Already registered? The task is part of a re-runnable workflow.
    Map existing = api(applianceUrl, apiToken, "GET",
            "/api/storage-buckets?name=" + URLEncoder.encode(bucketLabel, "UTF-8"), null, TIMEOUT)
    if (existing.code == 200 && (existing.json?.storageBuckets ?: []).any { it.name == bucketLabel }) {
        println "Storage Bucket '${bucketLabel}' is already registered in Morpheus"
        return
    }

    // createBucket stays false: the bucket itself was created by the previous task.
    String body = JsonOutput.toJson([
        storageBucket: [
            name: bucketLabel,
            providerType: "s3",
            bucketName: bucket,
            createBucket: false,
            config: [
                accessKey: bucketKey,
                secretKey: bucketSecret,
                endpoint: endpoint,
                region: REGION,
            ],
        ],
    ])
    Map res = api(applianceUrl, apiToken, "POST", "/api/storage-buckets", body, TIMEOUT)
    if (res.code < 200 || res.code >= 300 || res.json?.success == false) {
        throw new RuntimeException("POST /api/storage-buckets returned HTTP ${res.code}: ${apiError(res)}")
    }

    def created = res.json?.storageBucket
    println "Storage Bucket: ${created?.name ?: bucketLabel} (id ${created?.id ?: 'unknown'})"
    println "Endpoint      : ${endpoint}"
    println "Bucket        : ${bucket}, ${access} with access key ${bucketKey}"
    println "Browse it under Infrastructure > Storage > Buckets"
} catch (Throwable t) {
    throw new RuntimeException("rustfs_register_bucket: " + (t.message ?: t.getClass().name), t)
}
