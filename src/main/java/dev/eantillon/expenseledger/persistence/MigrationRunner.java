package dev.eantillon.expenseledger.persistence;

import dev.eantillon.expenseledger.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

public final class MigrationRunner {

    private static final List<Migration> MIGRATIONS = List.of(
            load("001", "/db/migration/V001__initial_schema.sql"));

    private final Database database;

    public MigrationRunner(Database database) {
        this.database = database;
    }

    public void migrate() {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                ensureMigrationTable(connection);
                for (Migration migration : MIGRATIONS) {
                    apply(connection, migration);
                }
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Database migration failed", exception);
        }
    }

    public boolean isCurrent() {
        try (Connection connection = database.open()) {
            ensureMigrationTable(connection);
            for (Migration migration : MIGRATIONS) {
                String checksum = appliedChecksum(connection, migration.version());
                if (!migration.checksum().equals(checksum)) {
                    return false;
                }
            }
            return true;
        } catch (SQLException exception) {
            throw new DatabaseException("Cannot inspect database migrations", exception);
        }
    }

    private static void apply(Connection connection, Migration migration) throws SQLException {
        String applied = appliedChecksum(connection, migration.version());
        if (applied != null) {
            if (!applied.equals(migration.checksum())) {
                throw new IllegalStateException(
                        "Migration " + migration.version() + " checksum does not match the database");
            }
            return;
        }
        for (String sql : migration.sql().split("(?m)^--@statement\\s*$")) {
            if (!sql.isBlank()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql.trim());
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_migrations(version, checksum, applied_at) VALUES (?, ?, ?)")) {
            statement.setString(1, migration.version());
            statement.setString(2, migration.checksum());
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private static void ensureMigrationTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version TEXT PRIMARY KEY,
                        checksum TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    ) STRICT
                    """);
        }
    }

    private static String appliedChecksum(Connection connection, String version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT checksum FROM schema_migrations WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static Migration load(String version, String resource) {
        try (InputStream input = MigrationRunner.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing migration resource: " + resource);
            }
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return new Migration(version, sql, Hashing.sha256(sql));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load migration resource: " + resource, exception);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record Migration(String version, String sql, String checksum) {
    }
}
