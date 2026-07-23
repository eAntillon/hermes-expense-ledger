package dev.eantillon.expenseledger.domain;

public record DraftEditInput(
        String entryType,
        String amount,
        String currency,
        String occurredOn,
        String merchant,
        String category,
        String person,
        String note,
        String relatedEntryId) {
}
