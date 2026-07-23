package dev.eantillon.expenseledger.persistence;

import dev.eantillon.expenseledger.domain.CurrencySummary;
import dev.eantillon.expenseledger.domain.Draft;
import dev.eantillon.expenseledger.domain.DraftStatus;
import dev.eantillon.expenseledger.domain.EntryType;
import dev.eantillon.expenseledger.domain.LedgerEntry;
import dev.eantillon.expenseledger.domain.LedgerQuery;
import dev.eantillon.expenseledger.domain.LedgerStatus;
import dev.eantillon.expenseledger.domain.ValidatedDraft;
import dev.eantillon.expenseledger.domain.ValidationException;
import dev.eantillon.expenseledger.util.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class LedgerRepository {

    private final Database database;

    public LedgerRepository(Database database) {
        this.database = database;
    }

    public Draft createDraft(ValidatedDraft input, String actor) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                Draft existing = findDraftBySource(connection, input.sourceChannelId(), input.sourceMessageId())
                        .orElse(null);
                if (existing != null) {
                    connection.commit();
                    return existing;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO drafts(
                            id, entry_type, amount_minor, currency, occurred_on,
                            merchant, category, person, note, raw_text,
                            source_channel_id, source_message_id, related_entry_id,
                            status, version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 1, ?, ?)
                        """)) {
                    int index = 1;
                    statement.setString(index++, id);
                    setDraftFields(statement, index, input);
                    index += 12;
                    statement.setString(index++, now.toString());
                    statement.setString(index, now.toString());
                    statement.executeUpdate();
                }
                Draft created = findDraft(connection, id).orElseThrow();
                audit(connection, "draft", id, "CREATED", actor, draftPayload(created), now);
                connection.commit();
                return created;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Cannot create draft", exception);
        }
    }

    public Draft updateDraft(String id, int expectedVersion, ValidatedDraft input, String actor) {
        Instant now = Instant.now();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE drafts SET
                        entry_type = ?, amount_minor = ?, currency = ?, occurred_on = ?,
                        merchant = ?, category = ?, person = ?, note = ?,
                        raw_text = ?, source_channel_id = ?, source_message_id = ?,
                        related_entry_id = ?, version = version + 1, updated_at = ?
                    WHERE id = ? AND status = 'PENDING' AND version = ?
                    """)) {
                setDraftFields(statement, 1, input);
                statement.setString(13, now.toString());
                statement.setString(14, id);
                statement.setInt(15, expectedVersion);
                if (statement.executeUpdate() != 1) {
                    throw stateConflict(connection, id, expectedVersion);
                }
                Draft updated = findDraft(connection, id).orElseThrow();
                audit(connection, "draft", id, "EDITED", actor, draftPayload(updated), now);
                connection.commit();
                return updated;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Cannot update draft", exception);
        }
    }

    public Draft cancelDraft(String id, String actor) {
        Instant now = Instant.now();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE drafts
                    SET status = 'CANCELLED', version = version + 1, updated_at = ?
                    WHERE id = ? AND status = 'PENDING'
                    """)) {
                statement.setString(1, now.toString());
                statement.setString(2, id);
                int changed = statement.executeUpdate();
                Draft draft = findDraft(connection, id)
                        .orElseThrow(() -> new ValidationException("draft was not found"));
                if (changed == 0 && draft.status() != DraftStatus.CANCELLED) {
                    throw new ValidationException("only a pending draft can be cancelled");
                }
                if (changed == 1) {
                    audit(connection, "draft", id, "CANCELLED", actor, draftPayload(draft), now);
                }
                connection.commit();
                return draft;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Cannot cancel draft", exception);
        }
    }

    public LedgerEntry confirmDraft(String id, String actor) {
        Instant now = Instant.now();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE drafts
                        SET status = 'CONFIRMED', version = version + 1, updated_at = ?
                        WHERE id = ? AND status = 'PENDING'
                        """)) {
                    statement.setString(1, now.toString());
                    statement.setString(2, id);
                    changed = statement.executeUpdate();
                }

                if (changed == 0) {
                    Draft draft = findDraft(connection, id)
                            .orElseThrow(() -> new ValidationException("draft was not found"));
                    if (draft.status() == DraftStatus.CONFIRMED) {
                        LedgerEntry existing = findEntryByDraft(connection, id).orElseThrow();
                        connection.commit();
                        return existing;
                    }
                    throw new ValidationException("a cancelled draft cannot be confirmed");
                }

                Draft draft = findDraft(connection, id).orElseThrow();
                validateRelationship(connection, draft);
                String entryId = UUID.randomUUID().toString();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ledger_entries(
                            id, draft_id, entry_type, amount_minor, currency, occurred_on,
                            merchant, category, person, note, raw_text,
                            source_channel_id, source_message_id, related_entry_id,
                            status, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                        """)) {
                    int index = 1;
                    statement.setString(index++, entryId);
                    statement.setString(index++, draft.id());
                    statement.setString(index++, draft.entryType().name());
                    statement.setLong(index++, draft.amountMinor());
                    statement.setString(index++, draft.currency().getCurrencyCode());
                    statement.setString(index++, draft.occurredOn().toString());
                    setNullable(statement, index++, draft.merchant());
                    setNullable(statement, index++, draft.category());
                    setNullable(statement, index++, draft.person());
                    setNullable(statement, index++, draft.note());
                    statement.setString(index++, draft.rawText());
                    statement.setString(index++, draft.sourceChannelId());
                    statement.setString(index++, draft.sourceMessageId());
                    setNullable(statement, index++, draft.relatedEntryId());
                    statement.setString(index++, now.toString());
                    statement.setString(index, now.toString());
                    statement.executeUpdate();
                }
                LedgerEntry entry = findEntry(connection, entryId).orElseThrow();
                audit(connection, "draft", id, "CONFIRMED", actor, draftPayload(draft), now);
                audit(connection, "ledger_entry", entryId, "CREATED", actor, entryPayload(entry), now);
                connection.commit();
                return entry;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Cannot confirm draft", exception);
        }
    }

    public Optional<Draft> findDraft(String id) {
        try (Connection connection = database.open()) {
            return findDraft(connection, id);
        } catch (SQLException exception) {
            throw new DatabaseException("Cannot read draft", exception);
        }
    }

    public List<Draft> listPendingDrafts(int limit) {
        if (limit < 1 || limit > 200) {
            throw new ValidationException("limit must be between 1 and 200");
        }
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT * FROM drafts
                        WHERE status = 'PENDING'
                        ORDER BY updated_at DESC
                        LIMIT ?
                        """)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<Draft> drafts = new ArrayList<>();
                while (result.next()) {
                    drafts.add(readDraft(result));
                }
                return List.copyOf(drafts);
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Cannot list drafts", exception);
        }
    }

    public Optional<LedgerEntry> findEntry(String id) {
        try (Connection connection = database.open()) {
            return findEntry(connection, id);
        } catch (SQLException exception) {
            throw new DatabaseException("Cannot read ledger entry", exception);
        }
    }

    public List<LedgerEntry> listEntries(LedgerQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ledger_entries WHERE status = 'ACTIVE'");
        List<Object> parameters = new ArrayList<>();
        if (query.entryType() != null) {
            sql.append(" AND entry_type = ?");
            parameters.add(query.entryType().name());
        }
        if (query.currency() != null) {
            sql.append(" AND currency = ?");
            parameters.add(query.currency().getCurrencyCode());
        }
        if (query.from() != null) {
            sql.append(" AND occurred_on >= ?");
            parameters.add(query.from().toString());
        }
        if (query.to() != null) {
            sql.append(" AND occurred_on <= ?");
            parameters.add(query.to().toString());
        }
        sql.append(" ORDER BY occurred_on DESC, created_at DESC LIMIT ?");
        parameters.add(query.limit());

        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            try (ResultSet result = statement.executeQuery()) {
                List<LedgerEntry> entries = new ArrayList<>();
                while (result.next()) {
                    entries.add(readEntry(result));
                }
                return List.copyOf(entries);
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Cannot list ledger entries", exception);
        }
    }

    public List<CurrencySummary> summarize(LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("""
                SELECT currency,
                    SUM(CASE WHEN entry_type = 'EXPENSE' THEN amount_minor ELSE 0 END) AS expense_minor,
                    SUM(CASE WHEN entry_type = 'REFUND' THEN amount_minor ELSE 0 END) AS refund_minor,
                    SUM(CASE WHEN entry_type = 'LOAN' THEN amount_minor ELSE 0 END) AS loaned_minor,
                    SUM(CASE WHEN entry_type = 'LOAN_PAYMENT' THEN amount_minor ELSE 0 END) AS repaid_minor
                FROM ledger_entries
                WHERE status = 'ACTIVE'
                """);
        List<String> parameters = new ArrayList<>();
        if (from != null) {
            sql.append(" AND occurred_on >= ?");
            parameters.add(from.toString());
        }
        if (to != null) {
            sql.append(" AND occurred_on <= ?");
            parameters.add(to.toString());
        }
        sql.append(" GROUP BY currency ORDER BY currency");

        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setString(index + 1, parameters.get(index));
            }
            try (ResultSet result = statement.executeQuery()) {
                List<CurrencySummary> summaries = new ArrayList<>();
                while (result.next()) {
                    summaries.add(new CurrencySummary(
                            Currency.getInstance(result.getString("currency")),
                            result.getLong("expense_minor"),
                            result.getLong("refund_minor"),
                            result.getLong("loaned_minor"),
                            result.getLong("repaid_minor")));
                }
                return List.copyOf(summaries);
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Cannot summarize ledger", exception);
        }
    }

    private static void validateRelationship(Connection connection, Draft draft) throws SQLException {
        if (draft.relatedEntryId() == null) {
            return;
        }
        LedgerEntry related = findEntry(connection, draft.relatedEntryId())
                .orElseThrow(() -> new ValidationException("related ledger entry was not found"));
        if (related.status() != LedgerStatus.ACTIVE) {
            throw new ValidationException("related ledger entry is not active");
        }
        if (!related.currency().equals(draft.currency())) {
            throw new ValidationException("related movements must use the same currency");
        }
        EntryType expected = draft.entryType() == EntryType.LOAN_PAYMENT
                ? EntryType.LOAN : EntryType.EXPENSE;
        if (related.entryType() != expected) {
            throw new ValidationException(
                    draft.entryType() == EntryType.LOAN_PAYMENT
                            ? "a loan payment must reference a loan"
                            : "a linked refund must reference an expense");
        }
        long previouslyApplied = relatedTotal(connection, related.id(), draft.entryType());
        if (Math.addExact(previouslyApplied, draft.amountMinor()) > related.amountMinor()) {
            throw new ValidationException("movement exceeds the remaining related balance");
        }
    }

    private static long relatedTotal(Connection connection, String relatedId, EntryType type)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(amount_minor), 0)
                FROM ledger_entries
                WHERE related_entry_id = ? AND entry_type = ? AND status = 'ACTIVE'
                """)) {
            statement.setString(1, relatedId);
            statement.setString(2, type.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private static Optional<Draft> findDraft(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM drafts WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readDraft(result)) : Optional.empty();
            }
        }
    }

    private static Optional<Draft> findDraftBySource(
            Connection connection, String channelId, String messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM drafts
                WHERE source_channel_id = ? AND source_message_id = ?
                """)) {
            statement.setString(1, channelId);
            statement.setString(2, messageId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readDraft(result)) : Optional.empty();
            }
        }
    }

    private static Optional<LedgerEntry> findEntry(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM ledger_entries WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readEntry(result)) : Optional.empty();
            }
        }
    }

    private static Optional<LedgerEntry> findEntryByDraft(Connection connection, String draftId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM ledger_entries WHERE draft_id = ?")) {
            statement.setString(1, draftId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readEntry(result)) : Optional.empty();
            }
        }
    }

    private static Draft readDraft(ResultSet result) throws SQLException {
        return new Draft(
                result.getString("id"),
                EntryType.valueOf(result.getString("entry_type")),
                result.getLong("amount_minor"),
                Currency.getInstance(result.getString("currency")),
                LocalDate.parse(result.getString("occurred_on")),
                result.getString("merchant"),
                result.getString("category"),
                result.getString("person"),
                result.getString("note"),
                result.getString("raw_text"),
                result.getString("source_channel_id"),
                result.getString("source_message_id"),
                result.getString("related_entry_id"),
                DraftStatus.valueOf(result.getString("status")),
                result.getInt("version"),
                Instant.parse(result.getString("created_at")),
                Instant.parse(result.getString("updated_at")));
    }

    private static LedgerEntry readEntry(ResultSet result) throws SQLException {
        return new LedgerEntry(
                result.getString("id"),
                result.getString("draft_id"),
                EntryType.valueOf(result.getString("entry_type")),
                result.getLong("amount_minor"),
                Currency.getInstance(result.getString("currency")),
                LocalDate.parse(result.getString("occurred_on")),
                result.getString("merchant"),
                result.getString("category"),
                result.getString("person"),
                result.getString("note"),
                result.getString("raw_text"),
                result.getString("source_channel_id"),
                result.getString("source_message_id"),
                result.getString("related_entry_id"),
                LedgerStatus.valueOf(result.getString("status")),
                Instant.parse(result.getString("created_at")),
                Instant.parse(result.getString("updated_at")));
    }

    private static int setDraftFields(
            PreparedStatement statement, int start, ValidatedDraft input) throws SQLException {
        int index = start;
        statement.setString(index++, input.entryType().name());
        statement.setLong(index++, input.money().minorUnits());
        statement.setString(index++, input.money().currency().getCurrencyCode());
        statement.setString(index++, input.occurredOn().toString());
        setNullable(statement, index++, input.merchant());
        setNullable(statement, index++, input.category());
        setNullable(statement, index++, input.person());
        setNullable(statement, index++, input.note());
        statement.setString(index++, input.rawText());
        statement.setString(index++, input.sourceChannelId());
        statement.setString(index++, input.sourceMessageId());
        setNullable(statement, index++, input.relatedEntryId());
        return index;
    }

    private static void setNullable(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static ValidationException stateConflict(
            Connection connection, String id, int expectedVersion) throws SQLException {
        Draft current = findDraft(connection, id)
                .orElseThrow(() -> new ValidationException("draft was not found"));
        if (current.status() != DraftStatus.PENDING) {
            return new ValidationException("only a pending draft can be edited");
        }
        return new ValidationException(
                "draft version changed; expected " + expectedVersion + " but is " + current.version());
    }

    private static void audit(
            Connection connection,
            String entityType,
            String entityId,
            String action,
            String actor,
            Map<String, Object> payload,
            Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_events(
                    entity_type, entity_id, action, actor, payload_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, entityType);
            statement.setString(2, entityId);
            statement.setString(3, action);
            statement.setString(4, actor);
            statement.setString(5, Json.stringify(payload));
            statement.setString(6, now.toString());
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> draftPayload(Draft draft) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", draft.id());
        payload.put("entry_type", draft.entryType().wireName());
        payload.put("amount_minor", draft.amountMinor());
        payload.put("currency", draft.currency().getCurrencyCode());
        payload.put("occurred_on", draft.occurredOn());
        payload.put("merchant", draft.merchant());
        payload.put("category", draft.category());
        payload.put("person", draft.person());
        payload.put("note", draft.note());
        payload.put("raw_text", draft.rawText());
        payload.put("source_channel_id", draft.sourceChannelId());
        payload.put("source_message_id", draft.sourceMessageId());
        payload.put("related_entry_id", draft.relatedEntryId());
        payload.put("status", draft.status().name());
        payload.put("version", draft.version());
        return payload;
    }

    private static Map<String, Object> entryPayload(LedgerEntry entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", entry.id());
        payload.put("draft_id", entry.draftId());
        payload.put("entry_type", entry.entryType().wireName());
        payload.put("amount_minor", entry.amountMinor());
        payload.put("currency", entry.currency().getCurrencyCode());
        payload.put("occurred_on", entry.occurredOn());
        payload.put("merchant", entry.merchant());
        payload.put("category", entry.category());
        payload.put("person", entry.person());
        payload.put("note", entry.note());
        payload.put("raw_text", entry.rawText());
        payload.put("source_channel_id", entry.sourceChannelId());
        payload.put("source_message_id", entry.sourceMessageId());
        payload.put("related_entry_id", entry.relatedEntryId());
        payload.put("status", entry.status().name());
        return payload;
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
