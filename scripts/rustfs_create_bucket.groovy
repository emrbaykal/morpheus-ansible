// rustfs_create_bucket.groovy
// Morpheus Groovy Script Task. Creates a bucket on the RustFS instance this operational
// workflow runs against, plus a service account limited to that bucket with the key pair
// and the access level the user entered on the form.
//
// Task settings: CODE = rustfs_create_bucket, SOURCE = Local, no result type needed.
// Workflow: operational, target = the RustFS instance. Form inputs:
//   rustfs_bucket_name, rustfs_bucket_access_key, rustfs_bucket_secret_key,
//   rustfs_bucket_policy (read-write | read-only).
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
    return [code: code,
            body: stream != null ? stream.getText("UTF-8").trim() : "",
            serverDate: conn.getHeaderField("Date")]
}

// Says what was used for signing without ever printing the secret itself, so one failed run
// shows whether the credentials arrived intact.
String credentialReport(String accessKey, String secret, String serverDate) {
    boolean masked = secret ==~ /[*]+/
    return "signed as '${accessKey}' with a ${secret.length()}-character secret" +
            (masked ? " that is only mask characters - the value never left Morpheus" : "") +
            ", server time ${serverDate ?: 'unknown'}, appliance time ${new Date().toGMTString()}"
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
    String bucketKey = formOpts.rustfs_bucket_access_key?.toString()?.trim()
    String bucketSecret = formOpts.rustfs_bucket_secret_key?.toString()?.trim()
    String access = (formOpts.rustfs_bucket_policy ?: "read-write").toString().trim().toLowerCase()
    if (!bucket || !bucketKey || !bucketSecret) {
        throw new RuntimeException("rustfs_bucket_name, rustfs_bucket_access_key and " +
                "rustfs_bucket_secret_key are required form inputs")
    }
    // S3 naming rules, checked because the name goes straight into the URL.
    if (!(bucket ==~ /[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/)) {
        throw new RuntimeException("'${bucket}' is not a valid S3 bucket name")
    }
    if (!(bucketKey ==~ /[A-Za-z0-9]{5,64}/)) {
        throw new RuntimeException("the access key must be 5-64 letters and digits, without '/'")
    }
    if (bucketSecret.length() < 8) {
        throw new RuntimeException("the secret key must be at least 8 characters")
    }
    if (!(access in ["read-write", "read-only"])) {
        throw new RuntimeException("rustfs_bucket_policy must be read-write or read-only, not '${access}'")
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
        throw new RuntimeException("PUT ${endpoint}/${bucket} returned HTTP ${made.code}: ${made.body} " +
                "[" + credentialReport(rootKey, rootSecret, made.serverDate) + "]")
    }
    String bucketState = made.code == 200 ? "created" : "already existed"

    // 2. A service account limited to that bucket, with the key pair from the form.
    //    read-only gets listing and download, read-write also gets upload, delete and
    //    multipart. Neither level can delete the bucket itself or see other buckets.
    List bucketActions = ["s3:ListBucket", "s3:GetBucketLocation"]
    List objectActions = ["s3:GetObject"]
    if (access == "read-write") {
        bucketActions += ["s3:ListBucketMultipartUploads"]
        objectActions += ["s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload",
                          "s3:ListMultipartUploadParts"]
    }
    String body = JsonOutput.toJson([
        accessKey: bucketKey,
        secretKey: bucketSecret,
        name: bucket,
        description: "Created by Morpheus for bucket ${bucket} (${access})".toString(),
        policy: [
            Version: "2012-10-17",
            Statement: [
                [Effect: "Allow", Action: bucketActions, Resource: ["arn:aws:s3:::" + bucket]],
                [Effect: "Allow", Action: objectActions, Resource: ["arn:aws:s3:::" + bucket + "/*"]],
            ],
        ],
    ])
    Map acc = signedRequest("PUT", endpoint, ADMIN_PATH, body, rootKey, rootSecret, REGION, SERVICE, TIMEOUT)
    if (acc.code < 200 || acc.code >= 300) {
        throw new RuntimeException("PUT ${ADMIN_PATH} returned HTTP ${acc.code}: ${acc.body} " +
                "[" + credentialReport(rootKey, rootSecret, acc.serverDate) + "]")
    }

    println "Bucket    : ${bucket} (${bucketState})"
    println "Endpoint  : ${endpoint}"
    println "Console   : http://${node.internalIp}:${(opts.rustfs_console_port ?: '9001')}"
    println "Access key: ${bucketKey}"
    println "Access    : ${access} on ${bucket} only (the secret key is the one you entered)"
} catch (Throwable t) {
    throw new RuntimeException("rustfs_create_bucket: " + (t.message ?: t.getClass().name), t)
}
