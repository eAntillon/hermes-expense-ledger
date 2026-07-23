package dev.eantillon.expenseledger.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSchemasTest {

    @Test
    void acceptsACompleteModelProposal() {
        Map<String, Object> proposal = validProposal();

        var result = McpJsonDefaults.getSchemaValidator()
                .validate(ToolSchemas.createDraft(), proposal);

        assertTrue(result.valid(), result.errorMessage());
    }

    @Test
    void rejectsWrongTypesMissingFieldsAndAdditionalProperties() {
        Map<String, Object> numericAmount = validProposal();
        numericAmount.put("amount", 40);
        assertFalse(validate(numericAmount));

        Map<String, Object> missingRawText = validProposal();
        missingRawText.remove("raw_text");
        assertFalse(validate(missingRawText));

        Map<String, Object> inventedField = validProposal();
        inventedField.put("confirmed", true);
        assertFalse(validate(inventedField));

        Map<String, Object> wrongType = validProposal();
        wrongType.put("entry_type", "transfer");
        assertFalse(validate(wrongType));
    }

    private static boolean validate(Map<String, Object> proposal) {
        return McpJsonDefaults.getSchemaValidator()
                .validate(ToolSchemas.createDraft(), proposal)
                .valid();
    }

    private static Map<String, Object> validProposal() {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("entry_type", "expense");
        proposal.put("amount", "40");
        proposal.put("merchant", "pollo");
        proposal.put("raw_text", "compra pollo 40");
        proposal.put("source_channel_id", "123456789012345678");
        proposal.put("source_message_id", "223456789012345678");
        return proposal;
    }
}
