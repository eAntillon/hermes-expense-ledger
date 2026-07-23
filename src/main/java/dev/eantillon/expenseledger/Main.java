package dev.eantillon.expenseledger;

import dev.eantillon.expenseledger.config.AppConfig;
import dev.eantillon.expenseledger.mcp.ExpenseMcpServer;
import dev.eantillon.expenseledger.persistence.BackupRepository;
import dev.eantillon.expenseledger.persistence.Database;
import dev.eantillon.expenseledger.persistence.LedgerRepository;
import dev.eantillon.expenseledger.persistence.MigrationRunner;
import dev.eantillon.expenseledger.persistence.ReportingRepository;
import dev.eantillon.expenseledger.service.BackupService;
import dev.eantillon.expenseledger.service.HealthService;
import dev.eantillon.expenseledger.service.LedgerService;
import dev.eantillon.expenseledger.util.Json;
import dev.eantillon.expenseledger.validation.DraftValidator;
import dev.eantillon.expenseledger.web.DashboardServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String command = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (command.isEmpty() || "help".equals(command) || "--help".equals(command)) {
            usage();
            return;
        }
        if ("generate-token".equals(command)) {
            System.out.println(generateToken());
            return;
        }

        try {
            AppConfig config = AppConfig.fromEnvironment();
            Files.createDirectories(config.logDirectory());
            System.setProperty("expense.log.dir", config.logDirectory().toString());
            Logger logger = LoggerFactory.getLogger(Main.class);

            Database database = new Database(config.databasePath());
            MigrationRunner migrations = new MigrationRunner(database);
            migrations.migrate();
            LedgerRepository ledgerRepository = new LedgerRepository(database);
            ReportingRepository reportingRepository = new ReportingRepository(database);
            LedgerService ledger = new LedgerService(
                    ledgerRepository,
                    reportingRepository,
                    new DraftValidator(config, Clock.systemUTC()));
            BackupService backups = new BackupService(
                    config, database, new BackupRepository(database));
            HealthService health = new HealthService(config, database, migrations, backups);

            switch (command) {
                case "migrate" -> logger.info("Database migrations are current");
                case "health" -> {
                    var result = health.check();
                    System.out.println(Json.stringify(result));
                    if (!"healthy".equals(result.get("status"))) {
                        System.exit(1);
                    }
                }
                case "backup" -> System.out.println(Json.stringify(backups.create().asMap()));
                case "mcp" -> {
                    config.requireDiscordChannelId();
                    new ExpenseMcpServer(ledger, backups, health).start();
                }
                case "serve" -> {
                    config.validateDashboardBoundary();
                    DashboardServer dashboard = new DashboardServer(
                            config,
                            ledger,
                            ledgerRepository,
                            reportingRepository,
                            backups,
                            health);
                    dashboard.start();
                    Runtime.getRuntime().addShutdownHook(
                            new Thread(dashboard::close, "expense-dashboard-shutdown"));
                    new CountDownLatch(1).await();
                }
                default -> {
                    usage();
                    System.exit(2);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            System.err.println("Expense ledger failed: " + safeMessage(exception));
            System.exit(1);
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private static void usage() {
        System.err.println("""
                Usage: hermes-expense-ledger <command>

                Commands:
                  mcp             Run the Hermes STDIO MCP server
                  serve           Run the authenticated local dashboard
                  migrate         Apply checksum-protected database migrations
                  backup          Create a verified local backup
                  health          Check database integrity and runtime state
                  generate-token  Generate a 256-bit dashboard token
                  help            Show this help
                """);
    }
}
