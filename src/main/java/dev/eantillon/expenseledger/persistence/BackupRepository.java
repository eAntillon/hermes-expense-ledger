package dev.eantillon.expenseledger.persistence;

import dev.eantillon.expenseledger.domain.BackupRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public final class BackupRepository {

    private final Database database;

    public BackupRepository(Database database) {
        this.database = database;
    }

    public void started(String id, Instant createdAt) {
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO backup_runs(id, status, created_at)
                        VALUES (?, 'RUNNING', ?)
                        """)) {
            statement.setString(1, id);
            statement.setString(2, createdAt.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new DatabaseException("Cannot record backup start", exception);
        }
    }

    public void succeeded(
            String id, String path, String sha256, long sizeBytes, Instant completedAt) {
        finish(id, path, sha256, sizeBytes, "SUCCEEDED", null, completedAt);
    }

    public void failed(String id, String error, Instant completedAt) {
        finish(id, null, null, null, "FAILED", truncate(error), completedAt);
    }

    public Optional<BackupRecord> latest() {
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT * FROM backup_runs
                        ORDER BY created_at DESC
                        LIMIT 1
                        """);
                ResultSet result = statement.executeQuery()) {
            return result.next() ? Optional.of(read(result)) : Optional.empty();
        } catch (SQLException exception) {
            throw new DatabaseException("Cannot read backup status", exception);
        }
    }

    private void finish(
            String id,
            String path,
            String sha256,
            Long sizeBytes,
            String status,
            String error,
            Instant completedAt) {
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE backup_runs
                        SET path = ?, sha256 = ?, size_bytes = ?, status = ?, error = ?, completed_at = ?
                        WHERE id = ?
                        """)) {
            nullable(statement, 1, path);
            nullable(statement, 2, sha256);
            if (sizeBytes == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, sizeBytes);
            }
            statement.setString(4, status);
            nullable(statement, 5, error);
            statement.setString(6, completedAt.toString());
            statement.setString(7, id);
            if (statement.executeUpdate() != 1) {
                throw new DatabaseException("Backup run was not found", new IllegalStateException(id));
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Cannot record backup outcome", exception);
        }
    }

    private static BackupRecord read(ResultSet result) throws SQLException {
        long size = result.getLong("size_bytes");
        boolean sizeWasNull = result.wasNull();
        return new BackupRecord(
                result.getString("id"),
                result.getString("path"),
                result.getString("sha256"),
                sizeWasNull ? null : size,
                result.getString("status"),
                result.getString("error"),
                Instant.parse(result.getString("created_at")),
                result.getString("completed_at") == null
                        ? null : Instant.parse(result.getString("completed_at")));
    }

    private static void nullable(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "Unknown backup failure";
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
