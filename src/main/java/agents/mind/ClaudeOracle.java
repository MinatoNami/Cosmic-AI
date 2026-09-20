package agents.mind;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.EnvironmentVariables;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Asks Claude.
 *
 * The only class in the project that touches the Anthropic SDK, so everything else can be
 * tested and run without a key.
 */
public class ClaudeOracle implements Oracle {
    private static final Logger log = LoggerFactory.getLogger(ClaudeOracle.class);

    private static final String MODEL = "claude-opus-5";

    /**
     * An agent decides many times a minute and each decision is a choice among a dozen typed
     * intents - closer to classification than to reasoning. Low effort suits that, and keeps
     * a population of agents affordable to run for hours. Raise it if agents start needing
     * to plan rather than react.
     */
    private static final OutputConfig.Effort EFFORT = OutputConfig.Effort.LOW;

    /** Small replies, but thinking tokens count towards this too, so leave headroom. */
    private static final long MAX_TOKENS = 4096L;

    private final AnthropicClient client;

    public ClaudeOracle() {
        this(defaultClient());
    }

    /**
     * Takes the key from the environment, or from the project's {@code .env} - the same file
     * the server reads its database settings from, and already gitignored.
     *
     * A key exported in your own shell does not reach a process someone else launches, which
     * makes .env the one place that works for both. The value is never logged.
     */
    private static AnthropicClient defaultClient() {
        String key = EnvironmentVariables.instance().getAll().get("ANTHROPIC_API_KEY");
        return key == null || key.isBlank()
                ? AnthropicOkHttpClient.fromEnv()
                : AnthropicOkHttpClient.builder().apiKey(key).build();
    }

    /** True when a key is reachable, so a caller can say so before burning a run. */
    public static boolean credentialsAvailable() {
        String fromEnvFile = EnvironmentVariables.instance().getAll().get("ANTHROPIC_API_KEY");
        return (fromEnvFile != null && !fromEnvFile.isBlank())
                || System.getenv("ANTHROPIC_API_KEY") != null;
    }

    public ClaudeOracle(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public String ask(String system, String user) {
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(MAX_TOKENS)
                    // The system prompt is identical on every call and is most of the input,
                    // so cache it: the volatile situation goes in the user message, after
                    // the breakpoint.
                    .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                            .text(system)
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build()))
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .outputConfig(OutputConfig.builder().effort(EFFORT).build())
                    .addUserMessage(user)
                    .build();

            Message response = client.messages().create(params);
            return response.content().stream()
                    .map(ContentBlock::text)
                    .flatMap(java.util.Optional::stream)
                    .map(text -> text.text())
                    .collect(Collectors.joining("\n"));
        } catch (RuntimeException e) {
            // Never fatal: an agent that cannot reach the model falls back to reflexes, the
            // way it would if it were simply not thinking very hard.
            log.warn("Could not reach the model, falling back", e);
            return null;
        }
    }

    @Override
    public String name() {
        return MODEL;
    }
}
