package dev.eantillon.expenseledger.domain;

import java.time.LocalDate;

public record ValidatedDraft(
        EntryType entryType,
        Money money,
        LocalDate occurredOn,
        String merchant,
        String category,
        String person,
        String note,
        String rawText,
        String sourceChannelId,
        String sourceMessageId,
        String relatedEntryId) {
}
