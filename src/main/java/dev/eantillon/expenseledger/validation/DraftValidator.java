package dev.eantillon.expenseledger.validation;

import dev.eantillon.expenseledger.config.AppConfig;
import dev.eantillon.expenseledger.domain.DraftInput;
import dev.eantillon.expenseledger.domain.EntryType;
import dev.eantillon.expenseledger.domain.Money;
import dev.eantillon.expenseledger.domain.ValidatedDraft;
import dev.eantillon.expenseledger.domain.ValidationException;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class DraftValidator {

    private static final Pattern DISCORD_ID = Pattern.compile("[0-9]{15,22}");
    private static final int MAX_RAW_TEXT = 4000;
    private static final int MAX_SHORT_TEXT = 160;
    private static final int MAX_NOTE = 1000;

    private final AppConfig config;
    private final Clock clock;

    public DraftValidator(AppConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ValidatedDraft validate(DraftInput input) {
        Objects.requireNonNull(input, "input");
        EntryType type = EntryType.parse(input.entryType());
        String currency = optional(input.currency(), 3);
        Money money = Money.parse(
                required(input.amount(), "amount", 80),
                currency == null ? config.baseCurrency().getCurrencyCode() : currency);
        LocalDate date = parseDate(input.occurredOn());
        String merchant = optional(input.merchant(), MAX_SHORT_TEXT);
        String category = optional(input.category(), MAX_SHORT_TEXT);
        String person = optional(input.person(), MAX_SHORT_TEXT);
        String note = optional(input.note(), MAX_NOTE);
        String rawText = requiredPreservingWhitespace(input.rawText(), "raw_text", MAX_RAW_TEXT);
        String sourceChannel = required(input.sourceChannelId(), "source_channel_id", 22);
        String sourceMessage = required(input.sourceMessageId(), "source_message_id", 22);
        String related = optional(input.relatedEntryId(), 36);

        if (!DISCORD_ID.matcher(sourceChannel).matches()) {
            throw new ValidationException("source_channel_id must be a Discord snowflake");
        }
        if (!sourceChannel.equals(config.requireDiscordChannelId())) {
            throw new ValidationException("writes are allowed only from the configured expense channel");
        }
        if (!DISCORD_ID.matcher(sourceMessage).matches()) {
            throw new ValidationException("source_message_id must be a Discord snowflake");
        }
        if (related != null) {
            validateUuid("related_entry_id", related);
        }
        if (type == EntryType.LOAN && person == null) {
            throw new ValidationException("person is required for a loan");
        }
        if (type == EntryType.LOAN_PAYMENT && related == null) {
            throw new ValidationException("related_entry_id is required for a loan payment");
        }
        if (type != EntryType.LOAN_PAYMENT && type != EntryType.REFUND && related != null) {
            throw new ValidationException("related_entry_id is valid only for refunds and loan payments");
        }

        return new ValidatedDraft(
                type,
                money,
                date,
                merchant,
                category,
                person,
                note,
                rawText,
                sourceChannel,
                sourceMessage,
                related);
    }

    private LocalDate parseDate(String raw) {
        LocalDate today = LocalDate.now(clock.withZone(config.timezone()));
        if (raw == null || raw.isBlank()) {
            return today;
        }
        try {
            LocalDate parsed = LocalDate.parse(raw.trim());
            if (parsed.isAfter(today)) {
                throw new ValidationException("occurred_on cannot be in the future");
            }
            return parsed;
        } catch (DateTimeException exception) {
            throw new ValidationException("occurred_on must use ISO format YYYY-MM-DD", exception);
        }
    }

    private static String required(String raw, String field, int maximumLength) {
        String value = optional(raw, maximumLength);
        if (value == null) {
            throw new ValidationException(field + " is required");
        }
        return value;
    }

    private static String requiredPreservingWhitespace(
            String raw, String field, int maximumLength) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException(field + " is required");
        }
        if (raw.length() > maximumLength) {
            throw new ValidationException(field + " exceeds " + maximumLength + " characters");
        }
        return raw;
    }

    private static String optional(String raw, int maximumLength) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > maximumLength) {
            throw new ValidationException("text field exceeds " + maximumLength + " characters");
        }
        return value;
    }

    private static void validateUuid(String field, String value) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(field + " must be a UUID", exception);
        }
    }
}
