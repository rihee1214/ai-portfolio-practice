package com.example.marketplace.phone;

import com.example.marketplace.phone.application.PhoneProvider;
import com.example.marketplace.phone.infrastructure.FirebasePhoneGateway;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhoneProviderEmulatorIT {
    @Test
    void browserSdkAndJavaVerifierEnforceProviderContract() throws Exception {
        String host = System.getenv("FIREBASE_AUTH_EMULATOR_HOST");
        String project = System.getenv("GCLOUD_PROJECT");
        assertNotNull(host, "Run through root verify.ps1 to start a fresh emulator");
        assertNotNull(project);
        try (var gateway = new FirebasePhoneGateway("local-test", project, host)) {
            var provider = new PhoneProvider("local-test", "127.0.0.1", Clock.systemUTC(), gateway);
            // This must succeed before the browser probe; the initial stub fails here.
            assertNotNull(provider.issue("01000000001").session());
            assertThrows(IllegalArgumentException.class, () -> new FirebasePhoneGateway("production", project, host));
            assertThrows(IllegalArgumentException.class, () -> new FirebasePhoneGateway("local-test", "real-project", host));
            assertThrows(IllegalArgumentException.class, () -> new FirebasePhoneGateway("local-test", project, "example.com:9099"));
            Gson gson = new Gson();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                    return;
                }
                int status = 200;
                String response;
                try {
                    var body = gson.fromJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                    if (exchange.getRequestURI().getPath().equals("/binding-issue")) {
                        response=gson.toJson(java.util.Map.of("session",gateway.issue(body.get("phone").getAsString())));
                    } else if(exchange.getRequestURI().getPath().equals("/binding-exchange")) {
                        response=gson.toJson(java.util.Map.of("customToken",gateway.exchange(body.get("session").getAsString(),body.get("code").getAsString())));
                    } else if(exchange.getRequestURI().getPath().equals("/binding-confirm")) {
                        response=gson.toJson(java.util.Map.of("phone",gateway.confirm(body.get("session").getAsString(),body.get("token").getAsString()).phone()));
                    } else if (exchange.getRequestURI().getPath().equals("/issue")) {
                        var request = provider.issue(body.get("phone").getAsString());
                        response = gson.toJson(java.util.Map.of("id", request.id(), "session", request.session()));
                    } else if (exchange.getRequestURI().getPath().equals("/exchange")) {
                        response = gson.toJson(java.util.Map.of("customToken", provider.exchange(body.get("id").getAsString(), body.get("code").getAsString())));
                    } else {
                        var proof = provider.confirm(body.get("id").getAsString(), body.get("token").getAsString());
                        response = gson.toJson(java.util.Map.of("phone", proof.phone(), "authenticatedAt", proof.authenticatedAt().toString()));
                    }
                } catch (RuntimeException rejected) {
                    status = 400;
                    response = "{\"error\":\"rejected\"}";
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            try {
                Path root = Path.of("..").toAbsolutePath().normalize();
                Path evidence = Path.of(System.getenv("A3_EVIDENCE"), "phone-browser.txt");
                Process process = new ProcessBuilder(root.resolve(".local-tools/node-v24.22.0-win-x64/node.exe").toString(),
                        root.resolve("verification/phone-browser.mjs").toString(),
                        "http://127.0.0.1:" + server.getAddress().getPort(), host, project)
                        .directory(root.toFile()).redirectErrorStream(true).redirectOutput(evidence.toFile()).start();
                boolean finished = process.waitFor(90, TimeUnit.SECONDS);
                if (!finished) { process.destroyForcibly(); }
                assertTrue(finished, "Phone browser probe exceeded its bound");
                assertEquals(0, process.exitValue(), "See phone-browser.txt; never contains raw codes or tokens");
            } finally {
                server.stop(0);
                executor.close();
            }
        }
    }
}
