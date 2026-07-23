package dev.eantillon.expenseledger.domain;

import java.util.Currency;

public record CurrencySummary(
        Currency currency,
        long expenseMinor,
        long refundMinor,
        long loanedMinor,
        long repaidMinor) {

    public long netSpentMinor() {
        return expenseMinor - refundMinor;
    }

    public long receivableMinor() {
        return loanedMinor - repaidMinor;
    }
}
