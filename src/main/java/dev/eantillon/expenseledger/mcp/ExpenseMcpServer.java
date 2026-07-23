package dev.eantillon.expenseledger.mcp;

import dev.eantillon.expenseledger.domain.DraftEditInput;
import dev.eantillon.expenseledger.domain.DraftInput;
import dev.eantillon.expenseledger.domain.EntryType;
import dev.eantillon.expenseledger.domain.LedgerQuery;
import dev.eantillon.expenseledger.domain.ValidationException;
import dev.eantillon.expenseledger.service.BackupService;
import dev.eantillon.expenseledger.service.HealthService;
import dev.eantillon.expenseledger.service.LedgerService;
import dev.eantillon.expenseledger.service.LedgerService.ServiceResult;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public final class ExpenseMcpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExpenseMcpServer.class);
    private static final String VERSION = "0.1.0-SNAPSHOT";

    private final LedgerService ledger;
    private final BackupService backups;
    private final HealthService health;
    private McpSyncServer server;

    public ExpenseMcpServer(
            LedgerService ledger, BackupService backups, HealthService health) {
        this.ledger = ledger;
        this.backups = backups;
        this.health = health;
    }

    public void start() {
        StdioServerTransportProvider transport =
                new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        server = McpServer.sync(transport)
                .serverInfo("hermes-expense-ledger", VERSION)
                .instructions("""
                        Financial writes always use a pending draft. Create one draft, show its exact
                        preview, and wait for an explicit user confirmation before confirming it.
                        Java domain validation is authoritative. Never convert currencies implicitly.
                        """)
                .validateToolInputs(true)
                .tools(tools())
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "expense-mcp-shutdown"));
        LOGGER.info("MCP server started");
    }

    public void close() {
        if (server != null) {
            server.closeGracefully();
            server = null;
        }
    }

    private List<McpServerFeatures.SyncToolSpecification> tools() {
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        tools.add(tool(
                "expense_draft_create",
                "Create a validated pending draft from one Discord expense message. This never records a ledger entry.",
                ToolSchemas.createDraft(),
                annotations(false, false, true),
                arguments -> {
                    DraftInput input = new DraftInput(
                            required(arguments, "entry_type"),
                            required(arguments, "amount"),
                            optional(arguments, "currency"),
                            optional(arguments, "occurred_on"),
                            optional(arguments, "merchant"),
                            optional(arguments, "category"),
                            optional(arguments, "person"),
                            optional(arguments, "note"),
                            required(arguments, "raw_text"),
                            required(arguments, "source_channel_id"),
                            required(arguments, "source_message_id"),
                            optional(arguments, "related_entry_id"));
                    return ledger.createDraft(input, "hermes:discord");
                }));
        tools.add(tool(
                "expense_draft_edit",
                "Replace the proposed fields of a pending draft and return a new preview. The original Discord text is preserved.",
                ToolSchemas.editDraft(),
                annotations(false, false, true),
                arguments -> ledger.editDraft(
                        required(arguments, "draft_id"),
                        requiredInteger(arguments, "expected_version"),
                        new DraftEditInput(
                                required(arguments, "entry_type"),
                                required(arguments, "amount"),
                                required(arguments, "currency"),
                                required(arguments, "occurred_on"),
                                optional(arguments, "merchant"),
                                optional(arguments, "category"),
                                optional(arguments, "person"),
                                optional(arguments, "note"),
                                optional(arguments, "related_entry_id")),
                        "hermes:discord")));
        tools.add(tool(
                "expense_draft_confirm",
                "Confirm a pending draft and atomically create its ledger entry. Call only after explicit user confirmation.",
                ToolSchemas.draftId(),
                annotations(false, false, true),
                arguments -> ledger.confirmDraft(required(arguments, "draft_id"), "hermes:discord")));
        tools.add(tool(
                "expense_draft_cancel",
                "Cancel a pending draft. Cancellation is idempotent.",
                ToolSchemas.draftId(),
                annotations(false, true, true),
                arguments -> ledger.cancelDraft(required(arguments, "draft_id"), "hermes:discord")));
        tools.add(tool(
                "expense_list",
                "List confirmed ledger entries using optional type, currency, and date filters.",
                ToolSchemas.listEntries(),
                annotations(true, false, true),
                arguments -> ledger.listEntries(new LedgerQuery(
                        parseType(optional(arguments, "entry_type")),
                        parseCurrency(optional(arguments, "currency")),
                        parseDate(optional(arguments, "from"), "from"),
                        parseDate(optional(arguments, "to"), "to"),
                        optionalInteger(arguments, "limit", 25)))));
        tools.add(tool(
                "expense_summary",
                "Summarize expenses, refunds, and receivables by currency. No FX conversion is performed.",
                ToolSchemas.summary(),
                annotations(true, false, true),
                arguments -> ledger.summary(
                        parseDate(optional(arguments, "from"), "from"),
                        parseDate(optional(arguments, "to"), "to"))));
        tools.add(tool(
                "expense_pending_list",
                "List pending drafts awaiting review.",
                ToolSchemas.noArguments(),
                annotations(true, false, true),
                arguments -> ledger.listPendingDrafts(50)));
        tools.add(tool(
                "backup_create",
                "Create an integrity-checked, compressed local SQLite backup and apply retention.",
                ToolSchemas.noArguments(),
                annotations(false, false, false),
                arguments -> {
                    BackupService.BackupResult result = backups.create();
                    return new ServiceResult("Local backup completed: " + result.path(), result.asMap());
                }));
        tools.add(tool(
                "backup_status",
                "Return the most recent local backup status.",
                ToolSchemas.noArguments(),
                annotations(true, false, true),
                arguments -> new ServiceResult("Local backup status.", backups.status())));
        tools.add(tool(
                "service_health",
                "Check SQLite integrity, schema migrations, configuration, and backup status.",
                ToolSchemas.noArguments(),
                annotations(true, false, true),
                arguments -> new ServiceResult("Expense ledger health check.", health.check())));
        return List.copyOf(tools);
    }

    private McpServerFeatures.SyncToolSpecification tool(
            String name,
            String description,
            Map<String, Object> schema,
            McpSchema.ToolAnnotations annotations,
            Function<Map<String, Object>, ServiceResult> handler) {
        McpSchema.Tool definition = McpSchema.Tool.builder(name, schema)
                .description(description)
                .annotations(annotations)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(definition)
                .callHandler((exchange, request) -> invoke(name, request.arguments(), handler))
                .build();
    }

    private McpSchema.CallToolResult invoke(
            String tool,
            Map<String, Object> arguments,
            Function<Map<String, Object>, ServiceResult> handler) {
        try {
            ServiceResult result = handler.apply(arguments);
            return McpSchema.CallToolResult.builder()
                    .textContent(List.of(result.text()))
                    .structuredContent(result.data())
                    .isError(false)
                    .build();
        } catch (ValidationException exception) {
            LOGGER.warn("Tool {} rejected input: {}", tool, exception.getMessage());
            return error("validation_error", exception.getMessage());
        } catch (RuntimeException exception) {
            LOGGER.error("Tool {} failed", tool, exception);
            return error("internal_error", "The expense ledger could not complete the operation.");
        }
    }

    private static McpSchema.CallToolResult error(String type, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", type);
        data.put("message", message);
        return McpSchema.CallToolResult.builder()
                .textContent(List.of(message))
                .structuredContent(data)
                .isError(true)
                .build();
    }

    private static McpSchema.ToolAnnotations annotations(
            boolean readOnly, boolean destructive, boolean idempotent) {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(readOnly)
                .destructiveHint(destructive)
                .idempotentHint(idempotent)
                .openWorldHint(false)
                .build();
    }

    private static String required(Map<String, Object> arguments, String name) {
        String value = optional(arguments, name);
        if (value == null) {
            throw new ValidationException(name + " is required");
        }
        return value;
    }

    private static String optional(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new ValidationException(name + " must be a string");
        }
        return text;
    }

    private static int requiredInteger(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value instanceof Number number) {
            try {
                return Math.toIntExact(number.longValue());
            } catch (ArithmeticException exception) {
                throw new ValidationException(name + " is outside the supported range");
            }
        }
        throw new ValidationException(name + " must be an integer");
    }

    private static int optionalInteger(
            Map<String, Object> arguments, String name, int fallback) {
        return arguments.containsKey(name) ? requiredInteger(arguments, name) : fallback;
    }

    private static EntryType parseType(String value) {
        return value == null ? null : EntryType.parse(value);
    }

    private static Currency parseCurrency(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Currency.getInstance(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("currency must be a valid ISO 4217 code");
        }
    }

    private static LocalDate parseDate(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException exception) {
            throw new ValidationException(field + " must use ISO format YYYY-MM-DD");
        }
    }
}
