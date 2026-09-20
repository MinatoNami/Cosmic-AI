package agents.mind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.EnvironmentVariables;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Asks a model served locally by LM Studio.
 *
 * LM Studio speaks the OpenAI chat-completions shape, so this is plain HTTP rather than a
 * vendor SDK. It sits behind {@link Oracle} exactly as {@link ClaudeOracle} does, which is
 * what that interface was for - the policy above it does not know or care which is answering.
 *
 * Two things a local model makes you confront that a hosted one hides. It may be a reasoning
 * model, in which case the answer arrives beside the reasoning rather than after it, and both
 * shapes are handled below. And it is slow - a 35B model on a laptop takes around fifteen
 * seconds per answer - which is why {@link LlmPolicy} asks in the background instead of
 * waiting.
 */
public class LmStudioOracle implements Oracle {
    private static final Logger log = LoggerFactory.getLogger(LmStudioOracle.class);

    private static final String DEFAULT_URL = "http://localhost:1234/v1/chat/completions";

    /**
     * Generous, because a reasoning model spends most of its budget thinking and returns an
     * empty answer if it runs out before reaching one. Found the hard way twice: at 300
     * tokens this model produced 1011 characters of reasoning and no content, and at 2000 it
     * did the same on a full-sized prompt. Measured need is around 700-2100 completion
     * tokens depending on how much context it is given.
     */
    private static final int MAX_TOKENS = 6000;

    /**
     * Low, because the reply has to match a fixed line format and because a reasoning model
     * rambles at higher temperatures - at 0.7 this model spent its whole 6000-token budget
     * thinking and returned nothing on every single call of a six-minute run; at 0.3 the same
     * prompt answers in about 2200 tokens.
     */
    private static final double TEMPERATURE = 0.3;

    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final URI endpoint;
    private final String model;

    public LmStudioOracle() {
        this(setting("LMSTUDIO_URL", DEFAULT_URL), setting("LMSTUDIO_MODEL", null));
    }

    public LmStudioOracle(String url, String model) {
        this.endpoint = URI.create(url);
        this.model = model;
    }

    private static String setting(String key, String fallback) {
        String value = EnvironmentVariables.instance().getAll().get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Asks the server what it has loaded, so the model name need not be configured. */
    public static String discoverModel(String url) {
        try {
            URI models = URI.create(url.replace("/chat/completions", "/models"));
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(models).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode data = new ObjectMapper().readTree(response.body()).path("data");
            return data.isArray() && !data.isEmpty() ? data.get(0).path("id").asText(null) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String ask(String system, String user) {
        try {
            ObjectNode body = json.createObjectNode();
            body.put("model", model != null ? model : "local-model");
            body.put("max_tokens", MAX_TOKENS);
            body.put("temperature", TEMPERATURE);

            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", system);
            messages.addObject().put("role", "user").put("content", user);

            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("LM Studio answered {}: {}", response.statusCode(),
                        abbreviate(response.body()));
                return null;
            }
            return answerFrom(json.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Could not reach LM Studio at {}, falling back", endpoint, e);
            return null;
        }
    }

    /**
     * Pulls the answer out, whichever way the server chose to separate it from the reasoning:
     * a {@code reasoning_content} field beside the answer, or {@code <think>} tags inside it.
     */
    private String answerFrom(JsonNode root) {
        JsonNode message = root.path("choices").path(0).path("message");
        String content = message.path("content").asText("");

        int end = content.lastIndexOf("</think>");
        if (end >= 0) {
            content = content.substring(end + "</think>".length());
        }
        content = content.trim();

        if (content.isEmpty()) {
            // A reasoning model that ran out of budget before answering. Worth saying plainly,
            // because the symptom otherwise is an agent that mysteriously never deliberates.
            log.warn("LM Studio returned no answer ({}). finish_reason={}",
                    message.hasNonNull("reasoning_content") ? "all of it went to reasoning" : "empty",
                    root.path("choices").path(0).path("finish_reason").asText("?"));
            return null;
        }
        return content;
    }

    private static String abbreviate(String text) {
        return text == null || text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    @Override
    public String name() {
        return "lmstudio:" + (model != null ? model : "local");
    }
}
