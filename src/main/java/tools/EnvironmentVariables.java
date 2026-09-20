package tools;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.HashMap;
import java.util.Map;

/**
 * Wrapper class for accessing environment variables.
 *
 * Using this class instead of calling built-in functions like `System.getenv()`
 * make it possible to mock environment variables in tests.
 *
 * Values declared in a `.env` file in the working directory are included as
 * well, which saves having to export variables by hand when running the server
 * outside of Docker. A real environment variable always wins over the `.env`
 * file, so a container or CI can override whatever the file says.
 *
 * Note that environment variables should only be accessed in limited places,
 * like configuration loading. In most places, you should use config instead
 * of environment variables.
 */
public class EnvironmentVariables {
    private static EnvironmentVariables instance;

    private final Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();

    private EnvironmentVariables() {}

    public static synchronized EnvironmentVariables instance() {
        if (instance == null) {
            instance = new EnvironmentVariables();
        }

        return instance;
    }

    public static void setInstance(EnvironmentVariables instance) {
        EnvironmentVariables.instance = instance;
    }

    public Map<String, String> getAll() {
        Map<String, String> variables = new HashMap<>(System.getenv());
        dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)
                .forEach(entry -> variables.putIfAbsent(entry.getKey(), entry.getValue()));
        return variables;
    }
}
