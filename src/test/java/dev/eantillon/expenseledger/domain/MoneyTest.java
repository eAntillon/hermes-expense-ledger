package dev.eantillon.expenseledger.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void parsesDecimalAmountsWithoutBinaryFloatingPoint() {
        Money money = Money.parse("140.1", "GTQ");

        assertEquals(14_010L, money.minorUnits());
        assertEquals("140.10", money.decimalAmount());
        assertEquals("GTQ 140.10", money.display());
    }

    @Test
    void rejectsUnsupportedPrecisionAndNumericSyntax() {
        assertThrows(ValidationException.class, () -> Money.parse("10.001", "GTQ"));
        assertThrows(ValidationException.class, () -> Money.parse("1e3", "GTQ"));
        assertThrows(ValidationException.class, () -> Money.parse("-10", "GTQ"));
        assertThrows(ValidationException.class, () -> Money.parse("0", "GTQ"));
    }

    @Test
    void respectsZeroDecimalCurrencies() {
        Money money = Money.parse("250", "JPY");

        assertEquals(250L, money.minorUnits());
        assertEquals("250", money.decimalAmount());
        assertThrows(ValidationException.class, () -> Money.parse("250.5", "JPY"));
    }
}
