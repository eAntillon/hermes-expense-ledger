package dev.eantillon.expenseledger;

import dev.eantillon.expenseledger.config.AppConfig;
import dev.eantillon.expenseledger.domain.Draft;
import dev.eantillon.expenseledger.domain.DraftInput;
import dev.eantillon.expenseledger.domain.EntryType;
import dev.eantillon.expenseledger.domain.ValidationException;
import dev.eantillon.expenseledger.persistence.BackupRepository;
import dev.eantillon.expenseledger.persistence.Database;
import dev.eantillon.expenseledger.persistence.LedgerRepository;
import dev.eantillon.expenseledger.persistence.MigrationRunner;
import dev.eantillon.expenseledger.persistence.ReportingRepository;
import dev.eantillon.expenseledger.service.BackupService;
import dev.eantillon.expenseledger.service.LedgerService;
import dev.eantillon.expenseledger.validation.DraftValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerIntegrationTest {

    private static final String CHANNEL = "123456789012345678";

    @TempDir
    Path temporary;

    private AppConfig config;
    private Database database;
    private LedgerRepository repository;
    private LedgerService service;

    @BeforeEach
    void setUp() {
        config = new AppConfig(
                Currency.getInstance("GTQ"),
                ZoneId.of("America/Guatemala"),
                Optional.of(CHANNEL),
                Set.of("323456789012345678"),
                temporary.resolve("data/ledger.db"),
                temporary.resolve("backups"),
                temporary.resolve("logs"),
                "127.0.0.1",
                8787,
                Optional.of("0123456789abcdef0123456789abcdef"),
                3,
                30,
                12);
        database = new Database(config.databasePath());
        MigrationRunner migrations = new MigrationRunner(database);
        migrations.migrate();
        assertTrue(migrations.isCurrent());
        repository = new LedgerRepository(database);
        service = new LedgerService(
                repository,
                new ReportingRepository(database),
                new DraftValidator(
                        config,
                        Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneId.of("UTC"))));
    }

    @Test
    void draftConfirmationIsExplicitAuditedAndIdempotent() throws Exception {
        DraftInput input = input(
                "expense", "40", "compra pollo 40", "223456789012345678", null, null);

        String draftId = service.createDraft(input, "test").data().get("id").toString();
        String replayedId = service.createDraft(input, "test").data().get("id").toString();
        assertEquals(draftId, replayedId);
        assertTrue(repository.listEntries(
                new dev.eantillon.expenseledger.domain.LedgerQuery(null, null, null, null, 20)).isEmpty());

        String entryId = service.confirmDraft(draftId, "test").data().get("id").toString();
        String repeatedEntryId = service.confirmDraft(draftId, "test").data().get("id").toString();
        assertEquals(entryId, repeatedEntryId);
        assertEquals(1, repository.listEntries(
                new dev.eantillon.expenseledger.domain.LedgerQuery(null, null, null, null, 20)).size());

        try (Connection connection = database.open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM audit_events")) {
            assertTrue(result.next());
            assertEquals(3, result.getInt(1));
        }
    }

    @Test
    void loanPaymentsCannotExceedTheReceivable() {
        String loanDraft = service.createDraft(
                input("loan", "3400", "prestamo ricardo 3400",
                        "223456789012345679", "Ricardo", null),
                "test").data().get("id").toString();
        String loanEntry = service.confirmDraft(loanDraft, "test").data().get("id").toString();

        String paymentDraft = service.createDraft(
                input("loan_payment", "400", "ricardo pago 400",
                        "223456789012345680", null, loanEntry),
                "test").data().get("id").toString();
        service.confirmDraft(paymentDraft, "test");

        String overpaymentDraft = service.createDraft(
                input("loan_payment", "3001", "ricardo pago 3001",
                        "223456789012345681", null, loanEntry),
                "test").data().get("id").toString();
        assertThrows(
                ValidationException.class,
                () -> service.confirmDraft(overpaymentDraft, "test"));

        var receivable = new ReportingRepository(database).openReceivables().getFirst();
        assertEquals(340_000L, receivable.originalMinor());
        assertEquals(40_000L, receivable.repaidMinor());
        assertEquals(300_000L, receivable.remainingMinor());
    }

    @Test
    void channelBoundaryIsEnforcedOutsideTheModelHarness() {
        DraftInput wrongChannel = new DraftInput(
                "expense",
                "321",
                null,
                null,
                "gas",
                "Transport",
                null,
                null,
                "gasto gas 321",
                "999999999999999999",
                "223456789012345682",
                null);

        assertThrows(ValidationException.class, () -> service.createDraft(wrongChannel, "test"));
    }

    @Test
    void localBackupIsCompressedChecksummedAndRecorded() {
        service.confirmDraft(
                service.createDraft(
                        input("expense", "140.1", "salida comer mc 140.1",
                                "223456789012345683", null, null),
                        "test").data().get("id").toString(),
                "test");
        BackupService backups = new BackupService(
                config, database, new BackupRepository(database));

        BackupService.BackupResult result = backups.create();

        assertTrue(Files.isRegularFile(result.path()));
        assertTrue(Files.isRegularFile(
                result.path().resolveSibling(result.path().getFileName() + ".sha256")));
        assertEquals("succeeded", backups.status().get("status"));
        assertTrue(result.sizeBytes() > 0);
        assertEquals(64, result.sha256().length());
    }

    private static DraftInput input(
            String type,
            String amount,
            String rawText,
            String messageId,
            String person,
            String relatedId) {
        String merchant = type.equals("expense") ? "sample" : null;
        return new DraftInput(
                type,
                amount,
                null,
                null,
                merchant,
                null,
                person,
                null,
                rawText,
                CHANNEL,
                messageId,
                relatedId);
    }
}
