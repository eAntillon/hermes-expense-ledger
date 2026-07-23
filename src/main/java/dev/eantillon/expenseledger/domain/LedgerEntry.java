package dev.eantillon.expenseledger.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;

public record LedgerEntry(
        String id,
        String draftId,
        EntryType entryType,
        long amountMinor,
        Currency currency,
        LocalDate occurredOn,
        String merchant,
        String category,
        String person,
        String note,
        String rawText,
        String sourceChannelId,
        String sourceMessageId,
        String relatedEntryId,
        LedgerStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public Money money() {
        return new Money(amountMinor, currency);
    }
}
