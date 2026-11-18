package id.stomake.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Local Ollama client. No prompts or character chats leave the user's computer. */
public final class OllamaClient {
    public static final String LIGHT_UNCENSORED = "richardyoung/qwen2.5-3b-instruct-abliterated:latest";
    public static final String RP_UNCENSORED = "R4C3R/gemma-3-4b-it-heretic:q4_k_m";
    public static final String RP_QUALITY = "R4C3R/qwen3-8b-heretic:q4_k_m";
    public static final String SMALL_ROLEPLAY = "nemotron-mini:4b";

    public record Message(String role, String content) {}

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String baseUrl = "http://localhost:11434";

    public void setBaseUrl(String value) {
        String v = value == null ? "" : value.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        if (v.isBlank()) v = "http://localhost:11434";
        this.baseUrl = v;
    }

    public String baseUrl() { return baseUrl; }

    public List<String> listModels() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("Ollama membalas HTTP " + response.statusCode());
        JsonNode models = mapper.readTree(response.body()).path("models");
        List<String> names = new ArrayList<>();
        if (models.isArray()) for (JsonNode model : models) {
            String name = model.path("name").asText("");
            if (!name.isBlank()) names.add(name);
        }
        return names;
    }

    public String chat(String model, List<Message> messages, double temperature, int contextSize, int maxTokens) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("stream", false);
        root.put("think", false);
        ArrayNode array = root.putArray("messages");
        for (Message message : messages) {
            ObjectNode item = array.addObject();
            item.put("role", message.role());
            item.put("content", message.content());
        }
        ObjectNode options = root.putObject("options");
        options.put("temperature", temperature);
        options.put("num_ctx", contextSize);
        options.put("num_predict", maxTokens);
        options.put("top_p", 0.92);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                .timeout(Duration.ofMinutes(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root))).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Ollama membalas HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonNode content = mapper.readTree(response.body()).path("message").path("content");
        String answer = content.asText("").trim();
        if (answer.isBlank()) throw new IllegalStateException("Model mengembalikan jawaban kosong. Coba matikan mode thinking atau gunakan model lain.");
        return answer;
    }

    public void pull(String model) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("name", model);
        root.put("stream", false);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/pull"))
                .timeout(Duration.ofMinutes(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root))).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Gagal mengunduh model (HTTP " + response.statusCode() + "): " + response.body());
        }
    }
}
