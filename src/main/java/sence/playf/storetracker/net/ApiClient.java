package sence.playf.storetracker.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sence.playf.storetracker.crypto.SecureChannel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class ApiClient {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public record Result(int statusCode, boolean networkError, String body) {
        public boolean isOk() {
            return statusCode == 200;
        }

        public boolean isNotFound() {
            return statusCode == 404;
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        public JsonElement bodyAsJson() {
            return body == null ? null : JsonParser.parseString(body);
        }
    }

    public static Result postEncrypted(String baseUrl, String path, SecureChannel channel, JsonElement body) {
        return postEncrypted(baseUrl, path, channel, body, null);
    }

    public static Result postEncrypted(String baseUrl, String path, SecureChannel channel, JsonElement body,
                                         String adminToken) {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("payload", channel.encryptJson(body));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json");
        if (adminToken != null && !adminToken.isBlank()) {
            builder.header("X-Admin-Token", adminToken);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(wrapper.toString())).build();

        return send(request);
    }

    public static Result get(String baseUrl, String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

        return send(request);
    }

    private static Result send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return new Result(response.statusCode(), false, response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Result(-1, true, null);
        }
    }

    private ApiClient() {
    }
}
