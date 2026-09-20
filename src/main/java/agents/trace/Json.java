package agents.trace;

import org.apache.commons.text.StringEscapeUtils;

import java.util.Collection;
import java.util.Map;

/**
 * Just enough JSON to write a trace line.
 *
 * The project has no JSON library and adding one to a game server for the sake of a side
 * feature is a poor trade. The genuinely risky part of hand-writing JSON is string escaping,
 * and commons-text - already a dependency - does that correctly, so what is left is
 * assembling braces.
 */
final class Json {
    private Json() {
    }

    static String object(Map<String, Object> fields) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            if (field.getValue() == null) {
                continue;
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(string(field.getKey())).append(':').append(value(field.getValue()));
        }
        return out.append('}').toString();
    }

    private static String value(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return object(typed);
        }
        if (value instanceof Collection<?> items) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(value(item));
            }
            return out.append(']').toString();
        }
        return string(value.toString());
    }

    private static String string(String raw) {
        return '"' + StringEscapeUtils.escapeJson(raw) + '"';
    }
}
