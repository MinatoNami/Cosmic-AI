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
 *
 * <p>Reasoning is switched off, through LM Studio's own API rather than the OpenAI-shaped one.
 * Both questions asked here want a line or two in a fixed format, and a reasoning model spent
 * two to six thousand tokens thinking before every one of them: thirty to ninety seconds an
 * answer, and one in three ran out of budget and answered nothing. With reasoning off the same
 * prompt is answered in under a second with a sensible action. The OpenAI-shaped endpoint
 * accepts {@code reasoning}, {@code reasoning_effort}, {@code /no_think} and
 * {@code enable_thinking} and honours none of them for this model, which is why this goes to
 * {@code /api/v1/chat}. An LM Studio too old to have that endpoint gets the old path.
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

    /** With nothing spent thinking, an answer is a few dozen tokens. Headroom, not a budget. */
    private static final int ANSWER_TOKENS = 1000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final URI endpoint;
    private final URI nativeEndpoint;
    private final String model;
    private final String reasoning;

    /** Cleared the first time the native endpoint turns out not to exist, and never retried. */
    private volatile boolean nativeAvailable = true;

    public LmStudioOracle() {
        this(setting("LMSTUDIO_URL", DEFAULT_URL), setting("LMSTUDIO_MODEL", null));
    }

    public LmStudioOracle(String url, String model) {
        this(url, model, setting("LMSTUDIO_REASONING", "off"));
    }

    /**
     * @param reasoning "off" or "on", as LM Studio names them. On is there for trying a model
     *                  that is no use without its thinking, not for everyday running.
     */
    public LmStudioOracle(String url, String model, String reasoning) {
        this.endpoint = URI.create(url);
        this.nativeEndpoint = endpoint.resolve("/api/v1/chat");
        this.model = model;
        this.reasoning = reasoning;
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
        if (nativeAvailable) {
            try {
                return askNatively(system, user);
            } catch (NoNativeEndpoint e) {
                nativeAvailable = false;
                log.warn("No {} on this LM Studio ({}), so reasoning cannot be switched off; "
                        + "using {} instead", nativeEndpoint, e.getMessage(), endpoint);
            }
        }
        return askOpenAiShaped(system, user);
    }

    /**
     * Holds the model to a JSON schema, which is also what keeps it from reasoning.
     *
     * Only the OpenAI-shaped endpoint takes a schema, and that endpoint will not switch
     * reasoning off - but a schema constrains the output from its first token, which leaves
     * no room to think: the deliberation prompt comes back as valid JSON in under a second.
     * LM Studio then files that JSON as reasoning rather than as the answer, because the
     * model's template opens in thinking mode, so the answer is read from either field.
     */
    @Override
    public String ask(String system, String user, String jsonSchema) {
        if (jsonSchema == null) {
            return ask(system, user);
        }
        try {
            ObjectNode body = json.createObjectNode();
            body.put("model", model != null ? model : "local-model");
            body.put("max_tokens", ANSWER_TOKENS);
            body.put("temperature", TEMPERATURE);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", system);
            messages.addObject().put("role", "user").put("content", user);
            ObjectNode format = body.putObject("response_format");
            format.put("type", "json_schema");
            ObjectNode schema = format.putObject("json_schema");
            schema.put("name", "answer");
            schema.put("strict", true);
            schema.set("schema", json.readTree(jsonSchema));

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
            JsonNode message = json.readTree(response.body()).path("choices").path(0).path("message");
            for (String field : new String[]{"content", "reasoning_content"}) {
                String text = message.path(field).asText("").trim();
                if (text.startsWith("{")) {
                    return text;
                }
            }
            log.warn("LM Studio returned no JSON answer. finish_reason={}",
                    json.readTree(response.body()).path("choices").path(0)
                            .path("finish_reason").asText("?"));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Could not reach LM Studio at {}, falling back", endpoint, e);
            return null;
        }
    }

    /** Thrown only when the endpoint is missing, so the fallback is not taken for a bad answer. */
    private static class NoNativeEndpoint extends Exception {
        NoNativeEndpoint(String message) {
            super(message);
        }
    }

    private String askNatively(String system, String user) throws NoNativeEndpoint {
        try {
            ObjectNode body = json.createObjectNode();
            body.put("model", model != null ? model : "local-model");
            body.put("system_prompt", system);
            body.put("input", user);
            body.put("reasoning", reasoning);
            body.put("temperature", TEMPERATURE);
            body.put("max_output_tokens", reasoning.equals("off") ? ANSWER_TOKENS : MAX_TOKENS);

            HttpRequest request = HttpRequest.newBuilder(nativeEndpoint)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new NoNativeEndpoint("404");
            }
            if (response.statusCode() != 200) {
                log.warn("LM Studio answered {}: {}", response.statusCode(),
                        abbreviate(response.body()));
                return null;
            }
            return nativeAnswerFrom(json.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (NoNativeEndpoint e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not reach LM Studio at {}, falling back", nativeEndpoint, e);
            return null;
        }
    }

    /** The message items of the output, joined; reasoning items, if any, are not the answer. */
    private String nativeAnswerFrom(JsonNode root) {
        StringBuilder answer = new StringBuilder();
        for (JsonNode item : root.path("output")) {
            if (item.path("type").asText().equals("message")) {
                answer.append(item.path("content").asText(""));
            }
        }
        String content = stripThinking(answer.toString());
        if (content.isEmpty()) {
            log.warn("LM Studio returned no answer ({} reasoning tokens)",
                    root.path("stats").path("reasoning_output_tokens").asText("?"));
            return null;
        }
        return content;
    }

    private String askOpenAiShaped(String system, String user) {
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
        String content = stripThinking(message.path("content").asText(""));

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

    private static String stripThinking(String content) {
        int end = content.lastIndexOf("</think>");
        return (end >= 0 ? content.substring(end + "</think>".length()) : content).trim();
    }

    private static String abbreviate(String text) {
        return text == null || text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    @Override
    public String name() {
        return "lmstudio:" + (model != null ? model : "local");
    }
}
