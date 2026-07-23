package dev.eantillon.expenseledger.persistence;

import dev.eantillon.expenseledger.domain.ReceivableBalance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

public final class ReportingRepository {

    private final Database database;

    public ReportingRepository(Database database) {
        this.database = database;
    }

    public List<ReceivableBalance> openReceivables() {
        String sql = """
                SELECT
                    loan.id,
                    loan.person,
                    loan.currency,
                    loan.amount_minor,
                    loan.occurred_on,
                    COALESCE(SUM(payment.amount_minor), 0) AS repaid_minor
                FROM ledger_entries loan
                LEFT JOIN ledger_entries payment
                    ON payment.related_entry_id = loan.id
                    AND payment.entry_type = 'LOAN_PAYMENT'
                    AND payment.status = 'ACTIVE'
                WHERE loan.entry_type = 'LOAN' AND loan.status = 'ACTIVE'
                GROUP BY loan.id
                HAVING loan.amount_minor > COALESCE(SUM(payment.amount_minor), 0)
                ORDER BY loan.occurred_on, loan.created_at
                """;
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            List<ReceivableBalance> balances = new ArrayList<>();
            while (result.next()) {
                balances.add(new ReceivableBalance(
                        result.getString("id"),
                        result.getString("person"),
                        Currency.getInstance(result.getString("currency")),
                        result.getLong("amount_minor"),
                        result.getLong("repaid_minor"),
                        LocalDate.parse(result.getString("occurred_on"))));
            }
            return List.copyOf(balances);
        } catch (SQLException exception) {
            throw new DatabaseException("Cannot list receivable balances", exception);
        }
    }
}
