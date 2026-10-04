package tech.zpkg.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Java 17+ client for the stable zed-pkg registry lifecycle.
 *
 * <p>The client never follows redirects, never includes the registry bearer on
 * artifact-download requests, bounds response bodies, and verifies artifact
 * SHA-256 values before returning bytes.</p>
 */
public final class ZedClient {
    public static final String DEFAULT_REGISTRY_URL = "https://registry.zpkg.tech";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    public static final int MAX_ARTIFACT_BYTES = 100 * 1024 * 1024;

    private static final int DOWNLOAD_SLACK_BYTES = 1024 * 1024;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 16 * 1024;
    private static final int MAX_SEGMENT_BYTES = 256;

    private final URI baseUri;
    private final String token;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ZedClient() {
        this(DEFAULT_REGISTRY_URL, null);
    }

    public ZedClient(String registryUrl, String token) {
        this(
                registryUrl,
                token,
                HttpClient.newBuilder()
                        .connectTimeout(DEFAULT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper()
        );
    }

    ZedClient(
            String registryUrl,
            String token,
            HttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.baseUri = validateBaseUri(registryUrl);
        this.token = normalizeOptional(token);
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public PackageMetadata getPackage(String org, String name)
            throws IOException, InterruptedException {
        return requestJson(
                "GET",
                packagePath(org, name),
                false,
                null,
                null,
                PackageMetadata.class
        );
    }

    public VersionMetadata getVersion(String org, String name, String version)
            throws IOException, InterruptedException {
        return requestJson(
                "GET",
                versionPath(org, name, version),
                false,
                null,
                null,
                VersionMetadata.class
        );
    }

    public SearchResponse search(String query) throws IOException, InterruptedException {
        if (query == null) {
            throw new ValidationException("query must not be null");
        }
        return requestJson(
                "GET",
                "/v1/search?q=" + encodeQuery(query),
                false,
                null,
                null,
                SearchResponse.class
        );
    }

    public ClaimOrgResponse claimOrg(String slug) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode().put("slug", requireSegment(slug, "slug"));
        return requestJson(
                "POST",
                "/v1/orgs",
                true,
                objectMapper.writeValueAsBytes(body),
                "application/json",
                ClaimOrgResponse.class
        );
    }

    public YankResponse setYanked(
            String org,
            String name,
            String version,
            boolean yanked
    ) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode().put("yanked", yanked);
        return requestJson(
                "POST",
                yankPath(org, name, version),
                true,
                objectMapper.writeValueAsBytes(body),
                "application/json",
                YankResponse.class
        );
    }

    public YankResponse yank(String org, String name, String version)
            throws IOException, InterruptedException {
        return setYanked(org, name, version, true);
    }

    public YankResponse restore(String org, String name, String version)
            throws IOException, InterruptedException {
        return setYanked(org, name, version, false);
    }

    /**
     * Download and verify an artifact. The registry bearer is deliberately not
     * attached because {@code download_url} may be a third-party presigned URL.
     */
    public byte[] downloadArtifact(VersionMetadata version)
            throws IOException, InterruptedException {
        Objects.requireNonNull(version, "version");
        URI downloadUri = downloadUri(version);
        int limit = downloadLimit(version.size());

        HttpRequest request = HttpRequest.newBuilder(downloadUri)
                .timeout(DEFAULT_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );
        try (InputStream stream = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw apiException(response.statusCode(), stream);
            }
            String declaredLength = response.headers().firstValue("content-length").orElse(null);
            if (declaredLength != null) {
                try {
                    if (Long.parseLong(declaredLength) > limit) {
                        throw new ArtifactTooLargeException(limit);
                    }
                } catch (NumberFormatException ignored) {
                    // A malformed content-length is not trusted; the streaming bound still applies.
                }
            }
            final byte[] artifact;
            try {
                artifact = readBounded(stream, limit, true);
            } catch (ResponseTooLargeException error) {
                throw new ArtifactTooLargeException(limit);
            }
            String actual = sha256Hex(artifact);
            if (!actual.equalsIgnoreCase(requireSegment(version.sha256(), "version.sha256"))) {
                throw new Sha256MismatchException(version.sha256(), actual);
            }
            return artifact;
        }
    }

    /**
     * Publish raw archive bytes as multipart fields {@code meta} and
     * {@code artifact}. The package coordinate is read from
     * {@code meta.manifest.package} and determines the PUT route.
     */
    public PublishResponse publish(JsonNode meta, byte[] artifact)
            throws IOException, InterruptedException {
        requireToken();
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(artifact, "artifact");
        if (!meta.isObject()) {
            throw new ValidationException("publish meta must be a JSON object");
        }
        if (artifact.length > MAX_ARTIFACT_BYTES) {
            throw new ArtifactTooLargeException(MAX_ARTIFACT_BYTES);
        }

        JsonNode pkg = meta.path("manifest").path("package");
        if (!pkg.isObject()) {
            throw new ValidationException("publish meta.manifest.package must be an object");
        }
        String org = requireSegment(pkg.path("org").asText(null), "meta.manifest.package.org");
        String name = requireSegment(pkg.path("name").asText(null), "meta.manifest.package.name");
        String version = requireSegment(
                pkg.path("version").asText(null),
                "meta.manifest.package.version"
        );

        String boundary = "zed-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, meta, artifact, org, name, version);
        return requestJson(
                "PUT",
                versionPath(org, name, version),
                true,
                body,
                "multipart/form-data; boundary=" + boundary,
                PublishResponse.class
        );
    }

    @Override
    public String toString() {
        return "ZedClient(baseUri=" + baseUri + ", token=[REDACTED])";
    }

    URI baseUri() {
        return baseUri;
    }

    private <T> T requestJson(
            String method,
            String path,
            boolean authenticated,
            byte[] body,
            String contentType,
            Class<T> type
    ) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = send(method, path, authenticated, body, contentType);
        try (InputStream stream = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw apiException(response.statusCode(), stream);
            }
            byte[] document = readBounded(stream, MAX_JSON_BYTES, true);
            try {
                return objectMapper.readValue(document, type);
            } catch (IOException error) {
                throw new ProtocolException("registry returned invalid JSON", error);
            }
        }
    }

    private HttpResponse<InputStream> send(
            String method,
            String path,
            boolean authenticated,
            byte[] body,
            String contentType
    ) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
                .timeout(DEFAULT_TIMEOUT)
                .header("Accept", "application/json");
        if (authenticated) {
            builder.header("Authorization", "Bearer " + requireToken());
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", Objects.requireNonNull(contentType, "contentType"))
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private ApiException apiException(int status, InputStream stream) throws IOException {
        byte[] bounded = readBounded(stream, MAX_ERROR_BYTES, false);
        String responseBody = new String(bounded, StandardCharsets.UTF_8)
                .replace('\r', ' ')
                .replace('\n', ' ');
        String code = "http_" + status;
        try {
            JsonNode value = objectMapper.readTree(bounded);
            if (value != null && value.path("code").isTextual()) {
                String candidate = value.path("code").asText().trim();
                if (!candidate.isEmpty()) {
                    code = candidate;
                }
            }
        } catch (IOException ignored) {
            // Non-JSON errors retain the stable HTTP-derived code.
        }
        return new ApiException(status, code, responseBody);
    }

    private URI resolve(String path) {
        return URI.create(baseUri.toString() + path);
    }

    private URI downloadUri(VersionMetadata version) {
        String raw = normalizeOptional(version.downloadUrl());
        if (raw == null) {
            return resolve(artifactPath(version.sha256()));
        }
        final URI uri;
        try {
            URI candidate = new URI(raw);
            if (candidate.isAbsolute()) {
                uri = candidate;
            } else {
                if (candidate.getRawAuthority() != null
                        || raw.startsWith("/")
                        || raw.startsWith("\\")) {
                    throw new ValidationException(
                            "relative download_url must not replace the registry authority or gateway path"
                    );
                }
                validateRelativeDownloadPath(raw);
                uri = URI.create(baseUri.toString() + "/").resolve(candidate);
            }
        } catch (URISyntaxException error) {
            throw new ValidationException("download_url is invalid", error);
        }
        return validateDownloadUri(uri);
    }

    private URI validateDownloadUri(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost")
                || host.startsWith("127.")
                || host.equals("::1");
        boolean allowed = scheme.equals("https")
                || (scheme.equals("http")
                && (loopback || baseUri.getScheme().equalsIgnoreCase("http")));
        if (!allowed
                || host.isBlank()
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null) {
            throw new ValidationException(
                    "download_url must use HTTPS; HTTP is allowed only for loopback or an HTTP development registry"
            );
        }
        return uri;
    }

    private static URI validateBaseUri(String input) {
        if (input == null) {
            throw new ValidationException("registry URL must not be null");
        }
        String trimmed = input.trim();
        validateRawBasePath(trimmed);
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        final URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException error) {
            throw new ValidationException("registry URL is invalid", error);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https"))
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new ValidationException(
                    "registry URL must be a credential-free absolute HTTP(S) URL without query or fragment"
            );
        }
        return uri;
    }

    private static void validateRawBasePath(String raw) {
        int schemeEnd = raw.indexOf("://");
        if (schemeEnd < 0) {
            return;
        }
        int authorityStart = schemeEnd + 3;
        int pathStart = raw.indexOf('/', authorityStart);
        if (pathStart < 0) {
            return;
        }
        int query = raw.indexOf('?', pathStart);
        int fragment = raw.indexOf('#', pathStart);
        int pathEnd = raw.length();
        if (query >= 0) {
            pathEnd = Math.min(pathEnd, query);
        }
        if (fragment >= 0) {
            pathEnd = Math.min(pathEnd, fragment);
        }
        validatePathSegments(raw.substring(pathStart, pathEnd), "registry URL path");
    }

    private static void validateRelativeDownloadPath(String raw) {
        int query = raw.indexOf('?');
        int fragment = raw.indexOf('#');
        int pathEnd = raw.length();
        if (query >= 0) {
            pathEnd = Math.min(pathEnd, query);
        }
        if (fragment >= 0) {
            pathEnd = Math.min(pathEnd, fragment);
        }
        validatePathSegments(raw.substring(0, pathEnd), "download_url");
    }

    private static void validatePathSegments(String rawPath, String name) {
        String[] segments = rawPath.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            String encoded = segments[index];
            if (encoded.isEmpty()) {
                continue;
            }
            final String decoded;
            try {
                decoded = URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException error) {
                throw new ValidationException(name + " contains invalid percent encoding", error);
            }
            requireSegment(decoded, name + " segment " + (index + 1));
            if (decoded.indexOf('/') >= 0 || decoded.indexOf('\\') >= 0) {
                throw new ValidationException(name + " segments must not contain encoded separators");
            }
        }
    }

    private static String packagePath(String org, String name) {
        return "/v1/packages/" + encodeSegment(org, "org") + "/" + encodeSegment(name, "name");
    }

    private static String versionPath(String org, String name, String version) {
        return packagePath(org, name) + "/versions/" + encodeSegment(version, "version");
    }

    private static String yankPath(String org, String name, String version) {
        return versionPath(org, name, version) + "/yank";
    }

    private static String artifactPath(String sha256) {
        return "/v1/artifacts/" + encodeSegment(sha256, "sha256");
    }

    private static String encodeSegment(String value, String name) {
        String checked = requireSegment(value, name);
        return URLEncoder.encode(checked, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String requireToken() {
        if (token == null) {
            throw new MissingTokenException();
        }
        if (token.codePoints().anyMatch(Character::isISOControl)) {
            throw new ValidationException("token must not contain control characters");
        }
        return token;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " must not be blank");
        }
        return value;
    }

    private static String requireSegment(String value, String name) {
        String checked = requireText(value, name);
        if (checked.equals(".") || checked.equals("..")) {
            throw new ValidationException(name + " must not be a dot segment");
        }
        if (checked.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES) {
            throw new ValidationException(name + " exceeds " + MAX_SEGMENT_BYTES + " UTF-8 bytes");
        }
        if (checked.codePoints().anyMatch(Character::isISOControl)) {
            throw new ValidationException(name + " must not contain control characters");
        }
        return checked;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int downloadLimit(long declaredSize) {
        if (declaredSize <= 0) {
            return MAX_ARTIFACT_BYTES;
        }
        long withSlack;
        try {
            withSlack = Math.addExact(declaredSize, DOWNLOAD_SLACK_BYTES);
        } catch (ArithmeticException ignored) {
            withSlack = Long.MAX_VALUE;
        }
        return (int) Math.min(withSlack, MAX_ARTIFACT_BYTES);
    }

    private byte[] multipartBody(
            String boundary,
            JsonNode meta,
            byte[] artifact,
            String org,
            String name,
            String version
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(artifact.length + 4096, MAX_ARTIFACT_BYTES)
        );
        writeAscii(output, "--" + boundary + "\r\n");
        writeAscii(output, "Content-Disposition: form-data; name=\"meta\"\r\n");
        writeAscii(output, "Content-Type: application/json\r\n\r\n");
        output.write(objectMapper.writeValueAsBytes(meta));
        writeAscii(output, "\r\n--" + boundary + "\r\n");
        String filename = safeFilename(org + "-" + name + "-" + version + ".tar.gz");
        writeAscii(
                output,
                "Content-Disposition: form-data; name=\"artifact\"; filename=\""
                        + filename
                        + "\"\r\n"
        );
        writeAscii(output, "Content-Type: application/octet-stream\r\n\r\n");
        output.write(artifact);
        writeAscii(output, "\r\n--" + boundary + "--\r\n");
        return output.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static String safeFilename(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static byte[] readBounded(InputStream input, int limit, boolean failOnOverflow)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int remaining = limit;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                return output.toByteArray();
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
        if (input.read() != -1 && failOnOverflow) {
            throw new ResponseTooLargeException(limit);
        }
        return output.toByteArray();
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PackageSummary(
            String org,
            String name,
            String description,
            String latest
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PackageMetadata(
            String org,
            String name,
            String description,
            String vcs,
            @JsonProperty("repo_url") String repoUrl,
            String latest,
            List<String> versions,
            @JsonProperty("version_scheme") String versionScheme
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VersionMetadata(
            String org,
            String name,
            String version,
            String sha256,
            long size,
            String format,
            @JsonProperty("vcs_tag") String vcsTag,
            @JsonProperty("vcs_commit") String vcsCommit,
            @JsonProperty("download_url") String downloadUrl,
            @JsonProperty("published_at") String publishedAt,
            boolean yanked
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResponse(String query, List<PackageSummary> items) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClaimOrgResponse(String slug, boolean created) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PublishResponse(String org, String name, String version, String sha256) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record YankResponse(String org, String name, String version, boolean yanked) { }

    public static final class ValidationException extends IllegalArgumentException {
        ValidationException(String message) {
            super(message);
        }

        ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class MissingTokenException extends IllegalStateException {
        MissingTokenException() {
            super("authenticated registry operation requires a bearer token");
        }
    }

    public static final class ApiException extends IOException {
        private final int status;
        private final String code;
        private final String responseBody;

        ApiException(int status, String code, String responseBody) {
            super("registry error " + status + ": " + code);
            this.status = status;
            this.code = code;
            this.responseBody = responseBody;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public String responseBody() {
            return responseBody;
        }
    }

    public static class ResponseTooLargeException extends IOException {
        private final int limit;

        ResponseTooLargeException(int limit) {
            super("registry response exceeded " + limit + " bytes");
            this.limit = limit;
        }

        public int limit() {
            return limit;
        }
    }

    public static final class ArtifactTooLargeException extends ResponseTooLargeException {
        ArtifactTooLargeException(int limit) {
            super(limit);
        }
    }

    public static final class Sha256MismatchException extends IOException {
        private final String expected;
        private final String actual;

        Sha256MismatchException(String expected, String actual) {
            super("artifact SHA-256 mismatch");
            this.expected = expected;
            this.actual = actual;
        }

        public String expected() {
            return expected;
        }

        public String actual() {
            return actual;
        }
    }

    public static final class ProtocolException extends IOException {
        ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
