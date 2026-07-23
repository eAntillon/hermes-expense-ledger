package dev.eantillon.expenseledger.domain;

import java.time.LocalDate;
import java.util.Currency;

public record LedgerQuery(
        EntryType entryType,
        Currency currency,
        LocalDate from,
        LocalDate to,
        int limit) {

    public LedgerQuery {
        if (limit < 1 || limit > 200) {
            throw new ValidationException("limit must be between 1 and 200");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationException("from cannot be after to");
        }
    }
}
