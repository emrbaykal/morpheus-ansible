// rustfs_create_bucket.groovy
// Morpheus Groovy Script Task. Creates a bucket on the RustFS instance this operational
// workflow runs against, plus a service account whose access is limited to that bucket.
// The generated access key and secret key are printed as the task result.
//
// Task settings: CODE = rustfs_create_bucket, SOURCE = Local, no result type needed.
// Workflow: operational, one form input (rustfs_bucket_name), target = the RustFS instance.
//
// Everything happens over the S3 and admin APIs with a self-signed AWS Signature V4
// request, so no SSH, no client binary and no stored credentials are involved. The root
// credentials come from the instance's own order options, read over the Morpheus API on
// the caller's token.
//
// Version history
//   <version: set by Emre>  2026-09-18  First version.

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.text.SimpleDateFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

// ---- Settings --------------------------------------------------------------------------
final String REGION       = "us-east-1"
final String SERVICE      = "s3"
final String ADMIN_PATH   = "/rustfs/admin/v3/add-service-account"
final int    TIMEOUT      = 20000

// ---- Helpers ---------------------------------------------------------------------------

String hex(byte[] data) {
    return data.collect { String.format("%02x", it) }.join()
}

String sha256Hex(String text) {
    return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8")))
}

byte[] hmac(byte[] key, String data) {
    Mac mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data.getBytes("UTF-8"))
}

// One signed request against the RustFS endpoint, per AWS Signature Version 4.
Map signedRequest(String method, String endpoint, String path, String body,
                  String accessKey, String secretKey, String region, String service, int timeout) {
    URL url = new URL(endpoint + path)
    String host = url.host + (url.port > 0 ? ":" + url.port : "")
    String payloadHash = sha256Hex(body ?: "")

    SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'")
    stamp.setTimeZone(TimeZone.getTimeZone("UTC"))
    String amzDate = stamp.format(new Date())
    String dateStamp = amzDate.substring(0, 8)

    String signedHeaders = "host;x-amz-content-sha256;x-amz-date"
    String canonicalRequest = [
        method, path, "",
        "host:" + host,
        "x-amz-content-sha256:" + payloadHash,
        "x-amz-date:" + amzDate,
        "", signedHeaders, payloadHash,
    ].join("\n")

    String scope = "${dateStamp}/${region}/${service}/aws4_request"
    String stringToSign = ["AWS4-HMAC-SHA256", amzDate, scope, sha256Hex(canonicalRequest)].join("\n")

    byte[] key = hmac(("AWS4" + secretKey).getBytes("UTF-8"), dateStamp)
    key = hmac(key, region)
    key = hmac(key, service)
    key = hmac(key, "aws4_request")

    HttpURLConnection conn = (HttpURLConnection) url.openConnection()
    conn.setRequestMethod(method)
    conn.setRequestProperty("Host", host)
    conn.setRequestProperty("x-amz-date", amzDate)
    conn.setRequestProperty("x-amz-content-sha256", payloadHash)
    conn.setRequestProperty("Authorization", "AWS4-HMAC-SHA256 Credential=${accessKey}/${scope}, " +
            "SignedHeaders=${signedHeaders}, Signature=${hex(hmac(key, stringToSign))}")
    conn.setConnectTimeout(timeout)
    conn.setReadTimeout(timeout)
    if (body) {
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setDoOutput(true)
        conn.getOutputStream().withStream { it.write(body.getBytes("UTF-8")) }
    }

    int code = conn.getResponseCode()
    InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()
    return [code: code, body: stream != null ? stream.getText("UTF-8").trim() : ""]
}

// The order options of this instance, read over the Morpheus API on the caller's token.
Map instanceOptions(String baseUrl, String token, def instanceId, int timeout) {
    def trustAll = [ new X509TrustManager() {
        X509Certificate[] getAcceptedIssuers() { return null }
        void checkClientTrusted(X509Certificate[] c, String a) {}
        void checkServerTrusted(X509Certificate[] c, String a) {}
    } ] as TrustManager[]
    SSLContext sc = SSLContext.getInstance("TLS")
    sc.init(null, trustAll, new java.security.SecureRandom())

    HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/instances/" + instanceId).openConnection()
    if (conn instanceof HttpsURLConnection) {
        conn.setSSLSocketFactory(sc.getSocketFactory())
        conn.setHostnameVerifier({ String h, SSLSession s -> true } as HostnameVerifier)
    }
    conn.setRequestProperty("Authorization", "Bearer " + token)
    conn.setConnectTimeout(timeout)
    conn.setReadTimeout(timeout)

    int code = conn.getResponseCode()
    if (code != 200) {
        throw new RuntimeException("GET /api/instances/${instanceId} returned HTTP ${code}")
    }
    def json = new JsonSlurper().parseText(conn.getInputStream().getText("UTF-8"))
    def opts = json?.instance?.config?.customOptions ?: json?.instance?.customOptions
    if (!(opts instanceof Map)) {
        throw new RuntimeException("instance ${instanceId} carries no customOptions")
    }
    return opts
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
    if (!bucket) {
        throw new RuntimeException("rustfs_bucket_name is empty; add it as a form input of this workflow")
    }
    // S3 naming rules, checked because the name goes straight into the URL.
    if (!(bucket ==~ /[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/)) {
        throw new RuntimeException("'${bucket}' is not a valid S3 bucket name")
    }

    String applianceUrl = (morpheus.applianceUrl ?: "").toString().replaceAll('/+$', '')
    String apiToken = (morpheus.apiAccessToken ?: "").toString()
    Map opts = instanceOptions(applianceUrl, apiToken, inst.id, TIMEOUT)

    String rootKey = opts.rustfs_access_key?.toString()?.trim()
    String rootSecret = opts.rustfs_secret_key?.toString()?.trim()
    String port = (opts.rustfs_s3_api_port ?: "9000").toString().trim()
    if (!rootKey || !rootSecret) {
        throw new RuntimeException("rustfs_access_key / rustfs_secret_key missing from the order options of ${inst.name}")
    }

    def node = inst.containers?.find { it.hostname == inst.name } ?: inst.containers?.getAt(0)
    if (!node?.internalIp) {
        throw new RuntimeException("no IP for the RustFS server in instance.containers")
    }
    String endpoint = "http://${node.internalIp}:${port}"

    // 1. The bucket. An existing bucket is fine, so the task can be run again.
    Map made = signedRequest("PUT", endpoint, "/" + bucket, null, rootKey, rootSecret, REGION, SERVICE, TIMEOUT)
    if (made.code != 200 && made.code != 409) {
        throw new RuntimeException("PUT ${endpoint}/${bucket} returned HTTP ${made.code}: ${made.body}")
    }
    String bucketState = made.code == 200 ? "created" : "already existed"

    // 2. A service account limited to that bucket. RustFS generates the key pair because
    //    the request carries neither accessKey nor secretKey.
    String policy = JsonOutput.toJson([
        Version: "2012-10-17",
        Statement: [[
            Effect: "Allow",
            Action: ["s3:*"],
            Resource: ["arn:aws:s3:::" + bucket, "arn:aws:s3:::" + bucket + "/*"],
        ]],
    ])
    String body = JsonOutput.toJson([
        policy: new JsonSlurper().parseText(policy),
        name: bucket,
        description: "Created by Morpheus for bucket " + bucket,
    ])
    Map acc = signedRequest("PUT", endpoint, ADMIN_PATH, body, rootKey, rootSecret, REGION, SERVICE, TIMEOUT)
    if (acc.code < 200 || acc.code >= 300) {
        throw new RuntimeException("PUT ${ADMIN_PATH} returned HTTP ${acc.code}: ${acc.body}")
    }

    def parsed = null
    try {
        parsed = new JsonSlurper().parseText(acc.body)
    } catch (Throwable ignored) {
        parsed = null
    }
    def creds = parsed?.credentials ?: parsed
    String newKey = creds?.accessKey ?: creds?.AccessKey
    String newSecret = creds?.secretKey ?: creds?.SecretKey
    if (!newKey || !newSecret) {
        throw new RuntimeException("service account created but the response carried no key pair: " +
                (acc.body.length() > 200 ? acc.body.substring(0, 200) + "..." : acc.body))
    }

    println "Bucket    : ${bucket} (${bucketState})"
    println "Endpoint  : ${endpoint}"
    println "Console   : http://${node.internalIp}:${(opts.rustfs_console_port ?: '9001')}"
    println "Access key: ${newKey}"
    println "Secret key: ${newSecret}"
    println "Scope     : full access to ${bucket} only"
} catch (Throwable t) {
    throw new RuntimeException("rustfs_create_bucket: " + (t.message ?: t.getClass().name), t)
}
