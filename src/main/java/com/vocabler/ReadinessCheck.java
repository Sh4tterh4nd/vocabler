package com.vocabler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Dependency-free readiness probe for Compose and other container runtimes. */
public final class ReadinessCheck {
    private ReadinessCheck() {}

    public static void main(String[] args) {
        int exitCode = 1;
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var port = System.getenv().getOrDefault("SERVER_PORT", "8080");
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            exitCode = response.statusCode() == 200 ? 0 : 1;
        } catch (Exception exception) {
            System.err.println("Readiness check failed: " + exception.getMessage());
        }
        System.exit(exitCode);
    }
}
