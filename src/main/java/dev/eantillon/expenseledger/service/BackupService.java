package dev.eantillon.expenseledger.service;

import dev.eantillon.expenseledger.config.AppConfig;
import dev.eantillon.expenseledger.domain.BackupRecord;
import dev.eantillon.expenseledger.persistence.BackupRepository;
import dev.eantillon.expenseledger.persistence.Database;
import dev.eantillon.expenseledger.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

public final class BackupService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AppConfig config;
    private final Database database;
    private final BackupRepository repository;

    public BackupService(AppConfig config, Database database, BackupRepository repository) {
        this.config = config;
        this.database = database;
        this.repository = repository;
    }

    public BackupResult create() {
        String runId = UUID.randomUUID().toString();
        Instant started = Instant.now();
        repository.started(runId, started);
        Path rawSnapshot = null;
        Path partialArchive = null;
        try {
            Files.createDirectories(config.backupDirectory());
            String timestamp = FILE_TIME.format(ZonedDateTime.now(config.timezone()));
            String baseName = "ledger-" + timestamp + "-" + runId.substring(0, 8);
            rawSnapshot = config.backupDirectory().resolve(baseName + ".db.tmp");
            Path finalArchive = config.backupDirectory().resolve(baseName + ".db.gz");
            partialArchive = config.backupDirectory().resolve(baseName + ".db.gz.partial");

            vacuumInto(rawSnapshot);
            verifyIntegrity(rawSnapshot);
            gzip(rawSnapshot, partialArchive);
            moveAtomically(partialArchive, finalArchive);
            String sha256 = Hashing.sha256(finalArchive);
            long size = Files.size(finalArchive);
            Files.writeString(
                    finalArchive.resolveSibling(finalArchive.getFileName() + ".sha256"),
                    sha256 + "  " + finalArchive.getFileName() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            Files.deleteIfExists(rawSnapshot);
            repository.succeeded(runId, finalArchive.toString(), sha256, size, Instant.now());
            applyRetention();
            return new BackupResult(runId, finalArchive, sha256, size);
        } catch (Exception exception) {
            deleteQuietly(rawSnapshot);
            deleteQuietly(partialArchive);
            repository.failed(runId, exception.getMessage(), Instant.now());
            throw new BackupException("Local backup failed", exception);
        }
    }

    public Map<String, Object> status() {
        BackupRecord record = repository.latest().orElse(null);
        if (record == null) {
            return Map.of("status", "never_run");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", record.id());
        result.put("status", record.status().toLowerCase());
        result.put("path", record.path());
        result.put("sha256", record.sha256());
        result.put("size_bytes", record.sizeBytes());
        result.put("error", record.error());
        result.put("created_at", record.createdAt().toString());
        result.put("completed_at", record.completedAt() == null
                ? null : record.completedAt().toString());
        return result;
    }

    private void vacuumInto(Path snapshot) throws SQLException {
        String escaped = snapshot.toString().replace("'", "''");
        try (Connection connection = database.open();
                Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + escaped + "'");
        }
    }

    private static void verifyIntegrity(Path snapshot) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + snapshot);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("Snapshot integrity check did not return ok");
            }
        }
    }

    private static void gzip(Path source, Path destination) throws IOException {
        try (InputStream input = Files.newInputStream(source);
                OutputStream file = Files.newOutputStream(destination);
                GZIPOutputStream gzip = new GZIPOutputStream(file)) {
            input.transferTo(gzip);
        }
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private void applyRetention() throws IOException {
        List<Path> archives;
        try (var files = Files.list(config.backupDirectory())) {
            archives = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches(
                            "ledger-[0-9]{8}-[0-9]{6}-[0-9a-f]{8}\\.db\\.gz"))
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .toList();
        }

        Set<Path> keep = new HashSet<>(
                archives.subList(0, Math.min(config.dailyRetention(), archives.size())));
        Set<YearMonth> keptMonths = new HashSet<>();
        for (Path archive : archives) {
            if (keptMonths.size() >= config.monthlyRetention()) {
                break;
            }
            YearMonth month = monthFromName(archive);
            if (keptMonths.add(month)) {
                keep.add(archive);
            }
        }
        for (Path archive : archives) {
            if (!keep.contains(archive)) {
                Files.deleteIfExists(archive);
                Files.deleteIfExists(archive.resolveSibling(archive.getFileName() + ".sha256"));
            }
        }
    }

    private YearMonth monthFromName(Path archive) {
        String date = archive.getFileName().toString().substring("ledger-".length(), 15);
        return YearMonth.of(
                Integer.parseInt(date.substring(0, 4)),
                Integer.parseInt(date.substring(4, 6)));
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException exception) {
            return Instant.EPOCH;
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // The original failure is more useful and the partial path is deterministic.
            }
        }
    }

    public record BackupResult(String id, Path path, String sha256, long sizeBytes) {

        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("path", path.toString());
            result.put("sha256", sha256);
            result.put("size_bytes", sizeBytes);
            return result;
        }
    }

    public static final class BackupException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public BackupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
