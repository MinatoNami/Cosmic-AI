package agents.mind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Talks to a stub LM Studio on a local port, so the request shape is pinned without a model.
 *
 * The thing worth pinning is that reasoning really is asked to be off, on the endpoint that
 * honours it - the OpenAI-shaped one accepted every way of saying so and ignored all of them.
 */
class LmStudioOracleTest {

    private HttpServer server;
    private final List<JsonNode> nativeRequests = new ArrayList<>();
    private final List<JsonNode> openAiRequests = new ArrayList<>();
    private boolean hasNativeEndpoint = true;
    private String nativeReply;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat", exchange -> {
            if (!hasNativeEndpoint) {
                respond(exchange, 404, "{\"error\":\"Unexpected endpoint or method.\"}");
                return;
            }
            nativeRequests.add(read(exchange));
            respond(exchange, 200, nativeReply);
        });
        server.createContext("/v1/chat/completions", exchange -> {
            openAiRequests.add(read(exchange));
            respond(exchange, 200, """
                    {"choices":[{"message":{"content":"<think>hmm</think>GOAL: old path"},
                     "finish_reason":"stop"}]}""");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private LmStudioOracle oracle() {
        return new LmStudioOracle("http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1/chat/completions", "qwen/test", "off");
    }

    @Test
    void asksWithReasoningOffAndReadsTheMessage() {
        nativeReply = """
                {"output":[{"type":"reasoning","content":"not this"},
                           {"type":"message","content":"GOAL: meet them\\nINTENT: TalkTo 904"}],
                 "stats":{"reasoning_output_tokens":0}}""";

        String answer = oracle().ask("system words", "what you see");

        assertEquals("GOAL: meet them\nINTENT: TalkTo 904", answer);
        JsonNode sent = nativeRequests.get(0);
        assertEquals("off", sent.path("reasoning").asText());
        assertEquals("system words", sent.path("system_prompt").asText());
        assertEquals("what you see", sent.path("input").asText());
        assertEquals("qwen/test", sent.path("model").asText());
        assertTrue(openAiRequests.isEmpty(), "the native endpoint answered, so nothing else is asked");
    }

    @Test
    void anEmptyAnswerIsNothingNotAnEmptyString() {
        nativeReply = "{\"output\":[],\"stats\":{\"reasoning_output_tokens\":0}}";

        assertNull(oracle().ask("s", "u"));
    }

    /** An older LM Studio has no native endpoint; the agent should still get answers. */
    @Test
    void usesTheOpenAiShapedEndpointWhenThereIsNoNativeOne() {
        hasNativeEndpoint = false;
        LmStudioOracle oracle = oracle();

        assertEquals("GOAL: old path", oracle.ask("s", "u"));
        assertEquals("GOAL: old path", oracle.ask("s", "u"));

        assertEquals(2, openAiRequests.size());
    }

    private static JsonNode read(HttpExchange exchange) throws IOException {
        return new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
