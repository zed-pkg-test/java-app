package tech.zpkg.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ZedClientHardeningTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void rejectsRawDotSegmentsEncodedSeparatorsAndHostileCoordinatesBeforeTransport() {
        for (String invalid : new String[] {
                "https://registry.example/../admin",
                "https://registry.example/%2e%2e/admin",
                "https://registry.example/a%2Fb"
        }) {
            assertThrows(ZedClient.ValidationException.class, () -> new ZedClient(invalid, null));
        }

        ZedClient client = new ZedClient("http://127.0.0.1:9", null);
        for (String hostile : new String[] {"", "   ", ".", "..", "line\nbreak"}) {
            assertThrows(
                    ZedClient.ValidationException.class,
                    () -> client.getPackage(hostile, "kit"),
                    hostile
            );
        }
        assertThrows(
                ZedClient.ValidationException.class,
                () -> client.getVersion("acme", "kit", "x".repeat(257))
        );
        assertThrows(ZedClient.MissingTokenException.class, () -> client.restore("acme", "kit", "1.0.0"));
        assertThrows(
                ZedClient.MissingTokenException.class,
                () -> client.publish(JSON.createObjectNode(), new byte[] {1})
        );

        ZedClient unsafeToken = new ZedClient(
                "http://127.0.0.1:9",
                "token\r\nInjected: header"
        );
        assertThrows(
                ZedClient.ValidationException.class,
                () -> unsafeToken.claimOrg("acme")
        );
    }

    @Test
    void relativeArtifactTraversalAndAuthorityReplacementFailBeforeTransport() {
        ZedClient client = new ZedClient("https://registry.example/gateway", null);
        for (String raw : new String[] {
                "../escape",
                "%2e%2e/escape",
                "a%2Fb",
                "//evil.example/artifact",
                "/absolute/artifact"
        }) {
            ZedClient.VersionMetadata version = new ZedClient.VersionMetadata(
                    "acme",
                    "kit",
                    "1.0.0",
                    "00",
                    1,
                    "tar.gz",
                    "v1.0.0",
                    null,
                    raw,
                    "now",
                    false
            );
            assertThrows(
                    ZedClient.ValidationException.class,
                    () -> client.downloadArtifact(version),
                    raw
            );
        }
    }

    @Test
    void relativeArtifactUrlPreservesGatewayAndAcceptsUppercaseDigest() throws Exception {
        byte[] artifact = "verified-artifact".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        try (Fixture fixture = Fixture.start(exchange -> {
            path.set(exchange.getRequestURI().getRawPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, artifact.length);
            exchange.getResponseBody().write(artifact);
            exchange.close();
        })) {
            ZedClient client = new ZedClient(fixture.baseUrl() + "/gateway", "registry-secret");
            ZedClient.VersionMetadata version = new ZedClient.VersionMetadata(
                    "acme",
                    "kit",
                    "1.0.0",
                    sha256(artifact).toUpperCase(),
                    artifact.length,
                    "tar.gz",
                    "v1.0.0",
                    null,
                    "artifact",
                    "now",
                    false
            );
            assertArrayEquals(artifact, client.downloadArtifact(version));
        }
        assertEquals("/gateway/artifact", path.get());
        assertNull(authorization.get(), "registry bearer leaked to artifact host");
    }

    @Test
    void blankStructuredErrorCodeFallsBackToHttpStatus() throws Exception {
        try (Fixture fixture = Fixture.start(exchange -> respond(
                exchange,
                409,
                "{\"code\":\"   \",\"message\":\"remote detail\"}"
        ))) {
            ZedClient client = new ZedClient(fixture.baseUrl(), "token");
            ZedClient.ApiException error = assertThrows(
                    ZedClient.ApiException.class,
                    () -> client.claimOrg("acme")
            );
            assertEquals("http_409", error.code());
            assertEquals("registry error 409: http_409", error.getMessage());
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
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
}
