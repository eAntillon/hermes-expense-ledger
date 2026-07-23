package dev.eantillon.expenseledger.service;

import dev.eantillon.expenseledger.config.AppConfig;
import dev.eantillon.expenseledger.persistence.Database;
import dev.eantillon.expenseledger.persistence.MigrationRunner;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HealthService {

    private final AppConfig config;
    private final Database database;
    private final MigrationRunner migrations;
    private final BackupService backups;

    public HealthService(
            AppConfig config,
            Database database,
            MigrationRunner migrations,
            BackupService backups) {
        this.config = config;
        this.database = database;
        this.migrations = migrations;
        this.backups = backups;
    }

    public Map<String, Object> check() {
        String integrity = database.integrityCheck();
        boolean current = migrations.isCurrent();
        boolean healthy = "ok".equalsIgnoreCase(integrity) && current;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", healthy ? "healthy" : "unhealthy");
        result.put("database", database.path().toString());
        result.put("database_exists", Files.isRegularFile(database.path()));
        result.put("sqlite_integrity", integrity);
        result.put("migrations_current", current);
        result.put("base_currency", config.baseCurrency().getCurrencyCode());
        result.put("timezone", config.timezone().getId());
        result.put("backup", backups.status());
        return result;
    }
}
