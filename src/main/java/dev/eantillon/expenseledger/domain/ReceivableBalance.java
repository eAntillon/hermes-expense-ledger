package dev.eantillon.expenseledger.domain;

import java.time.LocalDate;
import java.util.Currency;

public record ReceivableBalance(
        String loanEntryId,
        String person,
        Currency currency,
        long originalMinor,
        long repaidMinor,
        LocalDate occurredOn) {

    public long remainingMinor() {
        return originalMinor - repaidMinor;
    }
}
