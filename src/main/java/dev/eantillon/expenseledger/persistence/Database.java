package dev.eantillon.expenseledger.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class Database {

    private final Path path;

    public Database(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    public Path path() {
        return path;
    }

    public void prepareDirectory() {
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException exception) {
            throw new DatabaseException("Cannot create the database directory", exception);
        }
    }

    public Connection open() throws SQLException {
        prepareDirectory();
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        boolean success = false;
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            success = true;
            return connection;
        } finally {
            if (!success) {
                connection.close();
            }
        }
    }

    public String integrityCheck() {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            return result.next() ? result.getString(1) : "no result";
        } catch (SQLException exception) {
            throw new DatabaseException("SQLite integrity check failed", exception);
        }
    }
}
