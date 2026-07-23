package dev.eantillon.expenseledger.domain;

import java.util.Locale;

public enum EntryType {
    EXPENSE,
    REFUND,
    LOAN,
    LOAN_PAYMENT;

    public static EntryType parse(String value) {
        if (value == null) {
            throw new ValidationException("entry_type is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(
                    "entry_type must be expense, refund, loan, or loan_payment");
        }
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
