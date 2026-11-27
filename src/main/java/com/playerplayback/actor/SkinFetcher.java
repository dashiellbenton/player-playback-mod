package com.playerplayback.actor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Looks up a Minecraft account's current skin from Mojang's public API. */
public final class SkinFetcher {
    public record Skin(String value, String signature, String source) {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Map<String, CompletableFuture<Skin>> CACHE = new ConcurrentHashMap<>();

    private SkinFetcher() {}

    /** Fetches the skin of {@code username}. The future fails if the account doesn't exist. */
    public static CompletableFuture<Skin> fetch(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        CompletableFuture<Skin> future = CACHE.computeIfAbsent(key, k -> lookup(username));
        future.whenComplete((s, err) -> {
            if (err != null) CACHE.remove(key, future);
        });
        return future;
    }

    private static CompletableFuture<Skin> lookup(String username) {
        HttpRequest uuidReq = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return HTTP.sendAsync(uuidReq, HttpResponse.BodyHandlers.ofString()).thenCompose(resp -> {
            if (resp.statusCode() != 200) throw new IllegalArgumentException("No Minecraft account named " + username);
            JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
            String id = obj.get("id").getAsString();
            String realName = obj.has("name") ? obj.get("name").getAsString() : username;
            HttpRequest profileReq = HttpRequest.newBuilder(
                            URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            return HTTP.sendAsync(profileReq, HttpResponse.BodyHandlers.ofString()).thenApply(pr -> {
                if (pr.statusCode() != 200) throw new IllegalStateException("Mojang session server returned " + pr.statusCode());
                JsonObject profile = JsonParser.parseString(pr.body()).getAsJsonObject();
                JsonArray props = profile.getAsJsonArray("properties");
                for (JsonElement el : props) {
                    JsonObject p = el.getAsJsonObject();
                    if ("textures".equals(p.get("name").getAsString())) {
                        String sig = p.has("signature") ? p.get("signature").getAsString() : null;
                        return new Skin(p.get("value").getAsString(), sig, realName);
                    }
                }
                throw new IllegalStateException(realName + " has no skin");
            });
        });
    }
}
