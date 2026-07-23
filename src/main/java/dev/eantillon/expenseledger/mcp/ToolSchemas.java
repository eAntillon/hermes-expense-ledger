package dev.eantillon.expenseledger.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ToolSchemas {

    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";
    private static final String DISCORD_PATTERN = "^[0-9]{15,22}$";

    private ToolSchemas() {
    }

    static Map<String, Object> createDraft() {
        Map<String, Object> properties = commonMovementProperties();
        properties.put("raw_text", string(1, 4000, null, null));
        properties.put("source_channel_id", string(15, 22, DISCORD_PATTERN, null));
        properties.put("source_message_id", string(15, 22, DISCORD_PATTERN, null));
        return object(
                properties,
                List.of("entry_type", "amount", "raw_text", "source_channel_id", "source_message_id"));
    }

    static Map<String, Object> editDraft() {
        Map<String, Object> properties = commonMovementProperties();
        properties.put("draft_id", string(36, 36, UUID_PATTERN, null));
        properties.put("expected_version", integer(1, Integer.MAX_VALUE));
        return object(
                properties,
                List.of(
                        "draft_id",
                        "expected_version",
                        "entry_type",
                        "amount",
                        "currency",
                        "occurred_on"));
    }

    static Map<String, Object> draftId() {
        return object(
                Map.of("draft_id", string(36, 36, UUID_PATTERN, null)),
                List.of("draft_id"));
    }

    static Map<String, Object> listEntries() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("entry_type", entryType());
        properties.put("currency", string(3, 3, "^[A-Za-z]{3}$", null));
        properties.put("from", date());
        properties.put("to", date());
        properties.put("limit", integer(1, 200));
        return object(properties, List.of());
    }

    static Map<String, Object> summary() {
        return object(Map.of("from", date(), "to", date()), List.of());
    }

    static Map<String, Object> noArguments() {
        return object(Map.of(), List.of());
    }

    private static Map<String, Object> commonMovementProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("entry_type", entryType());
        properties.put("amount", string(1, 80, "^[0-9]+(?:\\.[0-9]+)?$", null));
        properties.put("currency", string(3, 3, "^[A-Za-z]{3}$", null));
        properties.put("occurred_on", date());
        properties.put("merchant", string(1, 160, null, null));
        properties.put("category", string(1, 160, null, null));
        properties.put("person", string(1, 160, null, null));
        properties.put("note", string(1, 1000, null, null));
        properties.put("related_entry_id", string(36, 36, UUID_PATTERN, null));
        return properties;
    }

    private static Map<String, Object> entryType() {
        return Map.of(
                "type", "string",
                "enum", List.of("expense", "refund", "loan", "loan_payment"));
    }

    private static Map<String, Object> date() {
        return string(10, 10, "^[0-9]{4}-[0-9]{2}-[0-9]{2}$", "date");
    }

    private static Map<String, Object> string(
            int minimum, int maximum, String pattern, String format) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("minLength", minimum);
        schema.put("maxLength", maximum);
        if (pattern != null) {
            schema.put("pattern", pattern);
        }
        if (format != null) {
            schema.put("format", format);
        }
        return schema;
    }

    private static Map<String, Object> integer(int minimum, int maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }

    private static Map<String, Object> object(
            Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }
}
