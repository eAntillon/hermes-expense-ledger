package dev.eantillon.expenseledger.util;

import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.Iterator;
import java.util.Map;

public final class Json {

    private Json() {
    }

    public static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        append(output, value);
        return output.toString();
    }

    private static void append(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String || value instanceof Character
                || value instanceof Enum<?> || value instanceof TemporalAccessor) {
            quote(output, value.toString());
        } else if (value instanceof Number || value instanceof Boolean) {
            output.append(value);
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            Iterator<? extends Map.Entry<?, ?>> entries = map.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<?, ?> entry = entries.next();
                quote(output, String.valueOf(entry.getKey()));
                output.append(':');
                append(output, entry.getValue());
                if (entries.hasNext()) {
                    output.append(',');
                }
            }
            output.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            output.append('[');
            Iterator<?> values = iterable.iterator();
            while (values.hasNext()) {
                append(output, values.next());
                if (values.hasNext()) {
                    output.append(',');
                }
            }
            output.append(']');
        } else if (value.getClass().isArray()) {
            output.append('[');
            for (int index = 0; index < Array.getLength(value); index++) {
                if (index > 0) {
                    output.append(',');
                }
                append(output, Array.get(value, index));
            }
            output.append(']');
        } else {
            quote(output, value.toString());
        }
    }

    private static void quote(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
