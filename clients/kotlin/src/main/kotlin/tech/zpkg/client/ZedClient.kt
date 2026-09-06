package tech.zpkg.client

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

class RegistryException(
    val status: Int,
    val code: String,
    val registryMessage: String,
) : RuntimeException("registry error $status: $code")

class ZedClient(
    registryUrl: String = "https://registry.zpkg.tech",
    private val token: String? = null,
    private val timeout: Duration = Duration.ofSeconds(30),
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) {
    private val base: URI = normalizeBase(registryUrl)

    fun packageJson(org: String, name: String): String =
        json("GET", "/v1/packages/${segment(org)}/${segment(name)}")

    fun versionJson(org: String, name: String, version: String): String =
        json("GET", "/v1/packages/${segment(org)}/${segment(name)}/versions/${segment(version)}")

    fun searchJson(query: String): String =
        json("GET", "/v1/search?q=${URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20")}")

    fun claimOrgJson(org: String): String =
        json("POST", "/v1/orgs", "{\"org\":${jsonString(org)}}", auth = true)

    fun setYankedJson(
        org: String,
        name: String,
        version: String,
        yanked: Boolean,
        reason: String? = null,
    ): String {
        val reasonField = reason?.let { ",\"reason\":${jsonString(it)}" } ?: ""
        return json(
            "POST",
            "/v1/packages/${segment(org)}/${segment(name)}/versions/${segment(version)}/yank",
            "{\"yanked\":$yanked$reasonField}",
            auth = true,
        )
    }

    fun yankJson(org: String, name: String, version: String, reason: String? = null): String =
        setYankedJson(org, name, version, true, reason)

    fun restoreJson(org: String, name: String, version: String): String =
        setYankedJson(org, name, version, false)

    fun publishJson(
        org: String,
        name: String,
        version: String,
        artifact: Path,
        metadataJson: String,
    ): String {
        require(Files.isRegularFile(artifact)) { "artifact must be a regular file" }
        val boundary = "zed-${UUID.randomUUID()}"
        val payload = multipart(boundary, metadataJson, artifact.fileName.toString(), Files.readAllBytes(artifact))
        return send(
            "PUT",
            "/v1/packages/${segment(org)}/${segment(name)}/versions/${segment(version)}",
            payload,
            "multipart/form-data; boundary=$boundary",
            auth = true,
            limit = MAX_JSON_BYTES,
        ).toString(StandardCharsets.UTF_8)
    }

    fun downloadArtifact(sha256: String, destination: Path? = null): ByteArray {
        val expected = sha256.lowercase()
        require(HEX_64.matches(expected)) { "sha256 must be 64 hexadecimal characters" }
        val bytes = send("GET", "/v1/artifacts/$expected", null, null, auth = false, limit = MAX_ARTIFACT_BYTES)
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw RegistryException(0, "digest_mismatch", "expected $expected, got $actual")
        }
        destination?.let { Files.write(it, bytes) }
        return bytes
    }

    private fun json(method: String, path: String, body: String? = null, auth: Boolean = false): String =
        send(
            method,
            path,
            body?.toByteArray(StandardCharsets.UTF_8),
            body?.let { "application/json" },
            auth,
            MAX_JSON_BYTES,
        ).toString(StandardCharsets.UTF_8)

    private fun send(
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String?,
        auth: Boolean,
        limit: Int,
    ): ByteArray {
        val builder = HttpRequest.newBuilder(base.resolve(base.path.trimEnd('/') + path))
            .timeout(timeout)
            .header("Accept", "application/json")
        if (contentType != null) builder.header("Content-Type", contentType)
        if (auth && !token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        builder.method(method, body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.body().size > limit) {
            throw RegistryException(0, "response_too_large", "response exceeded $limit bytes")
        }
        if (response.statusCode() !in 200..299) {
            val message = response.body().toString(StandardCharsets.UTF_8).take(16_384)
            throw RegistryException(response.statusCode(), "http_error", message)
        }
        return response.body()
    }

    private fun multipart(boundary: String, metadata: String, filename: String, artifact: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun text(value: String) = out.write(value.toByteArray(StandardCharsets.UTF_8))
        val safeFilename = filename.replace(Regex("[\\r\\n\\\"]"), "_")
        text("--$boundary\r\nContent-Disposition: form-data; name=\"meta\"\r\nContent-Type: application/json\r\n\r\n")
        text(metadata)
        text("\r\n--$boundary\r\nContent-Disposition: form-data; name=\"artifact\"; filename=\"$safeFilename\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        out.write(artifact)
        text("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun segment(value: String): String {
        require(value.isNotBlank() && value != "." && value != "..") { "path segment must be nonblank" }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= 256) { "path segment is too long" }
        require(value.none { it.code < 0x20 || it.code == 0x7f }) { "path segment contains a control character" }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    companion object {
        private const val MAX_JSON_BYTES = 16 * 1024 * 1024
        private const val MAX_ARTIFACT_BYTES = 100 * 1024 * 1024
        private val HEX_64 = Regex("^[0-9a-f]{64}$")

        private fun normalizeBase(raw: String): URI {
            val uri = URI(raw.trim())
            require(uri.scheme in setOf("http", "https") && uri.host != null) { "registryUrl must be absolute HTTP(S)" }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "registryUrl must not contain credentials, query, or fragment" }
            val cleanPath = uri.path.trimEnd('/')
            return URI(uri.scheme, null, uri.host, uri.port, cleanPath, null, null)
        }
    }
}
