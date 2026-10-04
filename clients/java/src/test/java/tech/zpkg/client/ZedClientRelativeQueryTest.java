package tech.zpkg.client;

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

final class ZedClientRelativeQueryTest {
    @Test
    void safeRelativePresignedQueryPreservesGatewayAndOmitsBearer() throws Exception {
        byte[] artifact = "query-artifact".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> rawPath = new AtomicReference<>();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(
                exchange,
                artifact,
                rawPath,
                rawQuery,
                authorization
        ));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/gateway";
            ZedClient client = new ZedClient(base, "registry-secret");
            ZedClient.VersionMetadata version = new ZedClient.VersionMetadata(
                    "acme",
                    "kit",
                    "1.0.0",
                    sha256(artifact),
                    artifact.length,
                    "tar.gz",
                    "v1.0.0",
                    null,
                    "artifacts/hash?signature=one",
                    "now",
                    false
            );

            assertArrayEquals(artifact, client.downloadArtifact(version));
            assertEquals("/gateway/artifacts/hash", rawPath.get());
            assertEquals("signature=one", rawQuery.get());
            assertNull(authorization.get(), "registry bearer leaked to artifact request");
        } finally {
            server.stop(0);
        }
    }

    private static void respond(
            HttpExchange exchange,
            byte[] body,
            AtomicReference<String> rawPath,
            AtomicReference<String> rawQuery,
            AtomicReference<String> authorization
    ) throws IOException {
        rawPath.set(exchange.getRequestURI().getRawPath());
        rawQuery.set(exchange.getRequestURI().getRawQuery());
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
