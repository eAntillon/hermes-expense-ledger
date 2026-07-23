package dev.eantillon.expenseledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;
import java.util.regex.Pattern;

public record Money(long minorUnits, Currency currency) {

    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final BigDecimal MAX_MAJOR_UNITS = new BigDecimal("1000000000000");

    public Money {
        if (minorUnits <= 0) {
            throw new ValidationException("amount must be greater than zero");
        }
    }

    public static Money parse(String amount, String currencyCode) {
        if (amount == null || amount.isBlank()) {
            throw new ValidationException("amount is required");
        }
        String normalizedAmount = amount.trim();
        if (!DECIMAL.matcher(normalizedAmount).matches()) {
            throw new ValidationException("amount must be a positive decimal string");
        }

        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new ValidationException("currency must be a valid ISO 4217 code", exception);
        }
        int fractionDigits = currency.getDefaultFractionDigits();
        if (fractionDigits < 0) {
            throw new ValidationException("currency does not define supported minor units");
        }

        try {
            BigDecimal decimal = new BigDecimal(normalizedAmount);
            if (decimal.signum() <= 0) {
                throw new ValidationException("amount must be greater than zero");
            }
            if (decimal.compareTo(MAX_MAJOR_UNITS) > 0) {
                throw new ValidationException("amount exceeds the supported maximum");
            }
            BigDecimal scaled = decimal.setScale(fractionDigits, RoundingMode.UNNECESSARY);
            return new Money(scaled.movePointRight(fractionDigits).longValueExact(), currency);
        } catch (ArithmeticException exception) {
            throw new ValidationException(
                    "amount has more fractional digits than " + currency.getCurrencyCode() + " supports",
                    exception);
        }
    }

    public String decimalAmount() {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits()).toPlainString();
    }

    public String display() {
        return currency.getCurrencyCode() + " " + decimalAmount();
    }
}
