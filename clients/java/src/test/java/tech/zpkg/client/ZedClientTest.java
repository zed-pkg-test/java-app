package tech.zpkg.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ZedClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void validatesAndNormalizesRegistryUrlsWithoutLeakingTokens() {
        ZedClient client = new ZedClient(
                " https://registry.example/gateway/// ",
                " very-secret "
        );
        assertEquals("https://registry.example/gateway", client.baseUri().toString());
        assertTrue(client.toString().contains("[REDACTED]"));
        assertFalse(client.toString().contains("very-secret"));

        for (String invalid : new String[] {
                "relative/path",
                "ftp://registry.example",
                "https://user:secret@registry.example",
                "https://registry.example?tenant=one",
                "https://registry.example#fragment"
        }) {
            assertThrows(ZedClient.ValidationException.class, () -> new ZedClient(invalid, null));
        }
    }

    @Test
    void publicReadsAndAuthenticatedMutationsUseTheExactCoreRoutes() throws Exception {
        List<Captured> requests = new CopyOnWriteArrayList<>();
        try (Fixture fixture = Fixture.start(exchange -> {
            Captured captured = Captured.from(exchange);
            requests.add(captured);
            String path = exchange.getRequestURI().getRawPath();
            if (path.endsWith("/yank")) {
                respond(
                        exchange,
                        200,
                        "{\"org\":\"acme\",\"name\":\"http-kit\","
                                + "\"version\":\"1.0.0\",\"yanked\":true}"
                );
            } else if (path.endsWith("/v1/orgs")) {
                respond(exchange, 200, "{\"slug\":\"acme\",\"created\":true}");
            } else {
                respond(
                        exchange,
                        200,
                        "{\"org\":\"acme\",\"name\":\"http-kit\","
                                + "\"vcs\":\"git\",\"repo_url\":\"https://example/acme/http-kit\","
                                + "\"versions\":[\"1.0.0\"]}"
                );
            }
        })) {
            ZedClient client = new ZedClient(fixture.baseUrl() + "/gateway///", "publisher-token");
            ZedClient.PackageMetadata metadata = client.getPackage("acme", "http kit");
            assertEquals("acme", metadata.org());
            assertEquals(List.of("1.0.0"), metadata.versions());
            assertTrue(client.claimOrg("acme").created());
            assertTrue(client.yank("acme", "http-kit", "1.0.0").yanked());
        }

        assertEquals("GET", requests.get(0).method());
        assertEquals("/gateway/v1/packages/acme/http%20kit", requests.get(0).path());
        assertNull(requests.get(0).authorization());

        assertEquals("POST", requests.get(1).method());
        assertEquals("/gateway/v1/orgs", requests.get(1).path());
        assertEquals("Bearer publisher-token", requests.get(1).authorization());
        assertEquals("acme", JSON.readTree(requests.get(1).body()).path("slug").asText());

        assertEquals(
                "/gateway/v1/packages/acme/http-kit/versions/1.0.0/yank",
                requests.get(2).path()
        );
        assertEquals("Bearer publisher-token", requests.get(2).authorization());
        assertTrue(JSON.readTree(requests.get(2).body()).path("yanked").asBoolean());
    }

    @Test
    void artifactDownloadNeverCarriesBearerAndVerifiesSha256() throws Exception {
        byte[] artifact = new byte[] {0, 1, 2, 3, (byte) 255};
        String digest = sha256(artifact);
        AtomicReference<String> authorization = new AtomicReference<>();
        try (Fixture fixture = Fixture.start(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, artifact.length);
            exchange.getResponseBody().write(artifact);
            exchange.close();
        })) {
            ZedClient client = new ZedClient(fixture.baseUrl(), "registry-secret");
            ZedClient.VersionMetadata version = new ZedClient.VersionMetadata(
                    "acme",
                    "http-kit",
                    "1.0.0",
                    digest,
                    artifact.length,
                    "tar.gz",
                    "v1.0.0",
                    null,
                    fixture.baseUrl() + "/presigned?signature=abc",
                    "2026-07-31T00:00:00Z",
                    false
            );
            assertArrayEquals(artifact, client.downloadArtifact(version));
        }
        assertNull(authorization.get(), "registry bearer leaked to artifact host");
    }

    @Test
    void artifactMismatchAndInsecureRemoteUrlsFailClosed() throws Exception {
        byte[] artifact = "artifact".getBytes(StandardCharsets.UTF_8);
        try (Fixture fixture = Fixture.start(exchange -> {
            exchange.sendResponseHeaders(200, artifact.length);
            exchange.getResponseBody().write(artifact);
            exchange.close();
        })) {
            ZedClient client = new ZedClient(fixture.baseUrl(), null);
            ZedClient.VersionMetadata mismatch = new ZedClient.VersionMetadata(
                    "acme",
                    "pkg",
                    "1.0.0",
                    "0".repeat(64),
                    artifact.length,
                    "tar.gz",
                    "v1.0.0",
                    null,
                    fixture.baseUrl() + "/artifact",
                    "now",
                    false
            );
            assertThrows(ZedClient.Sha256MismatchException.class, () -> client.downloadArtifact(mismatch));
        }

        ZedClient production = new ZedClient("https://registry.example", null);
        ZedClient.VersionMetadata insecure = new ZedClient.VersionMetadata(
                "acme",
                "pkg",
                "1.0.0",
                "0".repeat(64),
                1,
                "tar.gz",
                "v1.0.0",
                null,
                "http://artifacts.example/object",
                "now",
                false
        );
        assertThrows(ZedClient.ValidationException.class, () -> production.downloadArtifact(insecure));
    }

    @Test
    void multipartPublishPreservesRawArtifactBytesAndCoordinate() throws Exception {
        byte[] artifact = new byte[] {0, 7, 13, (byte) 200, (byte) 255};
        AtomicReference<Captured> captured = new AtomicReference<>();
        try (Fixture fixture = Fixture.start(exchange -> {
            captured.set(Captured.from(exchange));
            respond(
                    exchange,
                    200,
                    "{\"org\":\"acme\",\"name\":\"http-kit\","
                            + "\"version\":\"1.2.3\",\"sha256\":\"abc\"}"
            );
        })) {
            ZedClient client = new ZedClient(fixture.baseUrl() + "/registry", "publisher-token");
            JsonNode meta = JSON.readTree(
                    "{\"manifest\":{\"package\":{\"org\":\"acme\","
                            + "\"name\":\"http-kit\",\"version\":\"1.2.3\"}},"
                            + "\"provenance\":{\"commit\":\"deadbeef\"}}"
            );
            ZedClient.PublishResponse response = client.publish(meta, artifact);
            assertEquals("1.2.3", response.version());
        }

        Captured request = captured.get();
        assertEquals("PUT", request.method());
        assertEquals(
                "/registry/v1/packages/acme/http-kit/versions/1.2.3",
                request.path()
        );
        assertEquals("Bearer publisher-token", request.authorization());
        assertTrue(request.contentType().startsWith("multipart/form-data; boundary="));
        assertTrue(contains(request.bodyBytes(), artifact), "multipart body altered raw artifact bytes");
        String readable = new String(request.bodyBytes(), StandardCharsets.ISO_8859_1);
        assertTrue(readable.contains("name=\"meta\""));
        assertTrue(readable.contains("name=\"artifact\""));
        assertTrue(readable.contains("\"commit\":\"deadbeef\""));
    }

    @Test
    void defaultApiDiagnosticsHideRemoteBodyButRetainBoundedExplicitBody() throws Exception {
        try (Fixture fixture = Fixture.start(exchange -> respond(
                exchange,
                403,
                "{\"code\":\"scope_denied\",\"message\":\"provider-secret\"}"
        ))) {
            ZedClient client = new ZedClient(fixture.baseUrl(), "token");
            ZedClient.ApiException error = assertThrows(
                    ZedClient.ApiException.class,
                    () -> client.claimOrg("acme")
            );
            assertEquals(403, error.status());
            assertEquals("scope_denied", error.code());
            assertFalse(error.getMessage().contains("provider-secret"));
            assertTrue(error.responseBody().contains("provider-secret"));
        }
    }

    @Test
    void authenticatedOperationsRequireTokenBeforeTransport() {
        ZedClient client = new ZedClient("https://registry.example", null);
        assertThrows(ZedClient.MissingTokenException.class, () -> client.claimOrg("acme"));
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record Fixture(HttpServer server, String baseUrl) implements AutoCloseable {
        static Fixture start(Handler handler) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", handler::handle);
            server.start();
            return new Fixture(server, "http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record Captured(
            String method,
            String path,
            String authorization,
            String contentType,
            byte[] bodyBytes
    ) {
        static Captured from(HttpExchange exchange) throws IOException {
            return new Captured(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestBody().readAllBytes()
            );
        }

        String body() {
            return new String(bodyBytes, StandardCharsets.UTF_8);
        }
    }
}
