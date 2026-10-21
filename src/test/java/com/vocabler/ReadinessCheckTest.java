package com.vocabler;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadinessCheckTest {
    @ParameterizedTest
    @ValueSource(ints = {200, 503, 302})
    void succeedsOnlyForReadyResponse(int status) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/health/readiness", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            assertEquals(status == 200 ? 0 : 1, probe(server.getAddress().getPort()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsWhenApplicationIsUnavailable() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.start();
        server.stop(0);
        assertEquals(1, probe(port));
    }

    private int probe(int port) throws Exception {
        var classes = Path.of(ReadinessCheck.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var builder = new ProcessBuilder(java, "-cp", classes.toString(), ReadinessCheck.class.getName());
        builder.environment().put("SERVER_PORT", Integer.toString(port));
        builder.redirectErrorStream(true);
        var process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Readiness probe must finish within its timeout");
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }
}
