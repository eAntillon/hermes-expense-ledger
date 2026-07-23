package dev.eantillon.expenseledger.domain;

public record DraftInput(
        String entryType,
        String amount,
        String currency,
        String occurredOn,
        String merchant,
        String category,
        String person,
        String note,
        String rawText,
        String sourceChannelId,
        String sourceMessageId,
        String relatedEntryId) {
}
