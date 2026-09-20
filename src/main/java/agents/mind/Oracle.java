package agents.mind;

/**
 * Something that answers a question in text.
 *
 * A one-method seam between the policy and the model. It keeps the SDK in exactly one class,
 * lets the policy be tested without a network or an API key, and makes swapping in a
 * different model a matter of one implementation rather than a rewrite.
 */
public interface Oracle {

    /**
     * @return the reply, or null if the model could not be reached - callers must treat null
     *         as "decide some other way" rather than as a failure worth stopping for
     */
    String ask(String system, String user);

    String name();
}
