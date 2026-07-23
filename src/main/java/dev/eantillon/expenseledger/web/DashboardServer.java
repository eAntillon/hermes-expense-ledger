package dev.eantillon.expenseledger.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.eantillon.expenseledger.config.AppConfig;
import dev.eantillon.expenseledger.domain.CurrencySummary;
import dev.eantillon.expenseledger.domain.Draft;
import dev.eantillon.expenseledger.domain.DraftEditInput;
import dev.eantillon.expenseledger.domain.LedgerEntry;
import dev.eantillon.expenseledger.domain.LedgerQuery;
import dev.eantillon.expenseledger.domain.ReceivableBalance;
import dev.eantillon.expenseledger.domain.ValidationException;
import dev.eantillon.expenseledger.persistence.LedgerRepository;
import dev.eantillon.expenseledger.persistence.ReportingRepository;
import dev.eantillon.expenseledger.service.BackupService;
import dev.eantillon.expenseledger.service.HealthService;
import dev.eantillon.expenseledger.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class DashboardServer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardServer.class);

    private final AppConfig config;
    private final LedgerService service;
    private final LedgerRepository ledger;
    private final ReportingRepository reporting;
    private final BackupService backups;
    private final HealthService health;
    private final WebSessions sessions;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private HttpServer server;

    public DashboardServer(
            AppConfig config,
            LedgerService service,
            LedgerRepository ledger,
            ReportingRepository reporting,
            BackupService backups,
            HealthService health) {
        this.config = config;
        this.service = service;
        this.ledger = ledger;
        this.reporting = reporting;
        this.backups = backups;
        this.health = health;
        this.sessions = new WebSessions(config.requireWebAccessToken());
    }

    public void start() {
        try {
            server = HttpServer.create(
                    new InetSocketAddress(config.webBind(), config.webPort()), 0);
            server.createContext("/", this::route);
            server.setExecutor(requests);
            server.start();
            scheduleBackups();
            LOGGER.info("Dashboard started on {}:{}", config.webBind(), config.webPort());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start dashboard", exception);
        }
    }

    private void route(HttpExchange exchange) {
        try {
            securityHeaders(exchange);
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/healthz".equals(path) && "GET".equals(method)) {
                boolean healthy = "healthy".equals(health.check().get("status"));
                text(exchange, healthy ? 200 : 503, healthy ? "ok\n" : "unhealthy\n", "text/plain");
                return;
            }
            if ("/login".equals(path)) {
                login(exchange, method);
                return;
            }
            WebSessions.Session session = sessions.current(exchange).orElse(null);
            if (session == null) {
                redirect(exchange, "/login");
                return;
            }
            if ("/logout".equals(path) && "POST".equals(method)) {
                Map<String, String> form = FormData.read(exchange);
                requireCsrf(session, form);
                sessions.destroy(exchange);
                redirect(exchange, "/login");
                return;
            }
            if ("/".equals(path) && "GET".equals(method)) {
                dashboard(exchange, session);
                return;
            }
            if (path.startsWith("/draft/")) {
                draftRoute(exchange, session, path, method);
                return;
            }
            text(exchange, 404, Html.page("Not found", "<div class=\"card\"><h1>Not found</h1></div>"), "text/html");
        } catch (ValidationException | IllegalArgumentException exception) {
            respondError(exchange, 400, exception.getMessage());
        } catch (Exception exception) {
            LOGGER.error("Dashboard request failed", exception);
            respondError(exchange, 500, "The expense ledger could not complete the request.");
        } finally {
            exchange.close();
        }
    }

    private void login(HttpExchange exchange, String method) throws IOException {
        if ("GET".equals(method)) {
            String body = """
                    <div class="login card">
                      <h1>Expense Ledger</h1>
                      <p class="muted">Enter the local dashboard access token.</p>
                      <form method="post" action="/login">
                        <label for="token">Access token</label>
                        <input id="token" name="token" type="password" autocomplete="current-password" required>
                        <div class="actions"><button type="submit">Sign in</button></div>
                      </form>
                    </div>
                    """;
            text(exchange, 200, Html.page("Sign in", body), "text/html");
            return;
        }
        if (!"POST".equals(method)) {
            methodNotAllowed(exchange);
            return;
        }
        Map<String, String> form = FormData.read(exchange);
        if (!sessions.tokenMatches(form.get("token"))) {
            text(exchange, 401, Html.page(
                    "Sign in",
                    "<div class=\"login card\"><h1>Sign in failed</h1>"
                            + "<p class=\"error\">Invalid access token.</p>"
                            + "<a class=\"button\" href=\"/login\">Try again</a></div>"), "text/html");
            return;
        }
        sessions.create(exchange.getResponseHeaders());
        redirect(exchange, "/");
    }

    private void dashboard(HttpExchange exchange, WebSessions.Session session) throws IOException {
        List<Draft> drafts = ledger.listPendingDrafts(50);
        List<LedgerEntry> entries = ledger.listEntries(new LedgerQuery(null, null, null, null, 50));
        List<CurrencySummary> totals = ledger.summarize(null, null);
        List<ReceivableBalance> receivables = reporting.openReceivables();

        StringBuilder body = new StringBuilder();
        body.append("<header><div><h1>Expense Ledger</h1><div class=\"muted\">")
                .append(Html.escape(config.baseCurrency().getCurrencyCode()))
                .append(" · ").append(Html.escape(config.timezone().getId()))
                .append("</div></div>")
                .append("<form method=\"post\" action=\"/logout\"><input type=\"hidden\" name=\"csrf\" value=\"")
                .append(Html.escape(session.csrf()))
                .append("\"><button class=\"secondary\" type=\"submit\">Sign out</button></form></header>");

        body.append("<div class=\"grid\"><section class=\"card\"><h2>Totals by currency</h2>");
        if (totals.isEmpty()) {
            body.append("<p class=\"muted\">No confirmed movements.</p>");
        }
        for (CurrencySummary total : totals) {
            body.append("<p><strong>").append(Html.escape(total.currency().getCurrencyCode()))
                    .append("</strong><br>Net spent ")
                    .append(Html.escape(LedgerService.format(total.netSpentMinor(), total.currency())))
                    .append("<br>Receivable ")
                    .append(Html.escape(LedgerService.format(total.receivableMinor(), total.currency())))
                    .append("</p>");
        }
        body.append("</section><section class=\"card\"><h2>Open receivables</h2>");
        if (receivables.isEmpty()) {
            body.append("<p class=\"muted\">No open receivables.</p>");
        }
        for (ReceivableBalance balance : receivables) {
            body.append("<p><strong>").append(Html.escape(balance.person())).append("</strong><br>")
                    .append(Html.escape(LedgerService.format(balance.remainingMinor(), balance.currency())))
                    .append("<br><code>").append(Html.escape(balance.loanEntryId())).append("</code></p>");
        }
        body.append("</section><section class=\"card\"><h2>Backup</h2><p>")
                .append(Html.escape(backups.status().get("status")))
                .append("</p><p class=\"muted\">Local integrity-checked snapshots.</p></section></div>");

        body.append("<section class=\"card\" style=\"margin-top:16px\"><h2>Pending drafts</h2>");
        if (drafts.isEmpty()) {
            body.append("<p class=\"muted\">Nothing awaiting review.</p>");
        }
        body.append("<div class=\"scroll\"><table><thead><tr><th>Date</th><th>Type</th><th>Amount</th>")
                .append("<th>Details</th><th>Actions</th></tr></thead><tbody>");
        for (Draft draft : drafts) {
            body.append("<tr><td>").append(Html.escape(draft.occurredOn()))
                    .append("</td><td>").append(Html.escape(draft.entryType().wireName()))
                    .append("</td><td>").append(Html.escape(draft.money().display()))
                    .append("</td><td>").append(Html.escape(label(draft)))
                    .append("<br><code>").append(Html.escape(draft.id())).append("</code></td><td>")
                    .append("<div class=\"actions\"><a class=\"button secondary\" href=\"/draft/")
                    .append(Html.escape(draft.id())).append("\">Edit</a>")
                    .append(actionForm(draft.id(), "confirm", "Confirm", "", session.csrf()))
                    .append(actionForm(draft.id(), "cancel", "Cancel", "danger", session.csrf()))
                    .append("</div></td></tr>");
        }
        body.append("</tbody></table></div></section>");

        body.append("<section class=\"card\" style=\"margin-top:16px\"><h2>Recent entries</h2>")
                .append("<div class=\"scroll\"><table><thead><tr><th>Date</th><th>Type</th><th>Amount</th>")
                .append("<th>Details</th><th>Original message</th></tr></thead><tbody>");
        for (LedgerEntry entry : entries) {
            body.append("<tr><td>").append(Html.escape(entry.occurredOn()))
                    .append("</td><td>").append(Html.escape(entry.entryType().wireName()))
                    .append("</td><td>").append(Html.escape(entry.money().display()))
                    .append("</td><td>").append(Html.escape(label(entry)))
                    .append("<br><code>").append(Html.escape(entry.id())).append("</code></td><td>")
                    .append(Html.escape(entry.rawText())).append("</td></tr>");
        }
        body.append("</tbody></table></div></section>");
        text(exchange, 200, Html.page("Dashboard", body.toString()), "text/html");
    }

    private void draftRoute(
            HttpExchange exchange, WebSessions.Session session, String path, String method)
            throws IOException {
        String remainder = path.substring("/draft/".length());
        String[] parts = remainder.split("/");
        String id = parts[0];
        if (parts.length == 1 && "GET".equals(method)) {
            editPage(exchange, session, service.requireDraft(id));
            return;
        }
        if (parts.length != 2 || !"POST".equals(method)) {
            methodNotAllowed(exchange);
            return;
        }
        Map<String, String> form = FormData.read(exchange);
        requireCsrf(session, form);
        switch (parts[1]) {
            case "edit" -> service.editDraft(
                    id,
                    integer(form, "expected_version"),
                    new DraftEditInput(
                            form.get("entry_type"),
                            form.get("amount"),
                            form.get("currency"),
                            form.get("occurred_on"),
                            form.get("merchant"),
                            form.get("category"),
                            form.get("person"),
                            form.get("note"),
                            form.get("related_entry_id")),
                    "dashboard");
            case "confirm" -> service.confirmDraft(id, "dashboard");
            case "cancel" -> service.cancelDraft(id, "dashboard");
            default -> throw new ValidationException("Unknown draft action");
        }
        redirect(exchange, "/");
    }

    private void editPage(
            HttpExchange exchange, WebSessions.Session session, Draft draft) throws IOException {
        String body = """
                <header><div><h1>Edit draft</h1><div class="muted"><code>%s</code></div></div>
                <a class="button secondary" href="/">Back</a></header>
                <section class="card">
                  <p><strong>Original message:</strong> %s</p>
                  <form method="post" action="/draft/%s/edit">
                    <input type="hidden" name="csrf" value="%s">
                    <input type="hidden" name="expected_version" value="%d">
                    <label for="entry_type">Type</label>
                    <select id="entry_type" name="entry_type">%s</select>
                    <label for="amount">Amount</label>
                    <input id="amount" name="amount" inputmode="decimal" value="%s" required>
                    <label for="currency">Currency</label>
                    <input id="currency" name="currency" value="%s" maxlength="3" required>
                    <label for="occurred_on">Date</label>
                    <input id="occurred_on" name="occurred_on" type="date" value="%s" required>
                    <label for="merchant">Merchant or item</label>
                    <input id="merchant" name="merchant" value="%s">
                    <label for="category">Category</label>
                    <input id="category" name="category" value="%s">
                    <label for="person">Person</label>
                    <input id="person" name="person" value="%s">
                    <label for="note">Note</label>
                    <textarea id="note" name="note">%s</textarea>
                    <label for="related_entry_id">Related entry ID</label>
                    <input id="related_entry_id" name="related_entry_id" value="%s">
                    <div class="actions"><button type="submit">Save and preview</button></div>
                  </form>
                </section>
                """.formatted(
                Html.escape(draft.id()),
                Html.escape(draft.rawText()),
                Html.escape(draft.id()),
                Html.escape(session.csrf()),
                draft.version(),
                typeOptions(draft.entryType().wireName()),
                Html.escape(draft.money().decimalAmount()),
                Html.escape(draft.currency().getCurrencyCode()),
                Html.escape(draft.occurredOn()),
                Html.escape(draft.merchant()),
                Html.escape(draft.category()),
                Html.escape(draft.person()),
                Html.escape(draft.note()),
                Html.escape(draft.relatedEntryId()));
        text(exchange, 200, Html.page("Edit draft", body), "text/html");
    }

    private void scheduleBackups() {
        ZonedDateTime now = ZonedDateTime.now(config.timezone());
        ZonedDateTime next = now.withHour(config.backupHour()).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        long initialDelay = Duration.between(now, next).toSeconds();
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        backups.create();
                        LOGGER.info("Scheduled local backup completed");
                    } catch (RuntimeException exception) {
                        LOGGER.error("Scheduled local backup failed", exception);
                    }
                },
                initialDelay,
                Duration.ofDays(1).toSeconds(),
                TimeUnit.SECONDS);
    }

    private void requireCsrf(WebSessions.Session session, Map<String, String> form) {
        if (!sessions.validCsrf(session, form.get("csrf"))) {
            throw new ValidationException("Invalid CSRF token");
        }
    }

    private static int integer(Map<String, String> form, String field) {
        try {
            return Integer.parseInt(form.get(field));
        } catch (RuntimeException exception) {
            throw new ValidationException(field + " must be an integer");
        }
    }

    private static String actionForm(
            String id, String action, String label, String css, String csrf) {
        return "<form method=\"post\" action=\"/draft/" + Html.escape(id) + "/" + action + "\">"
                + "<input type=\"hidden\" name=\"csrf\" value=\"" + Html.escape(csrf) + "\">"
                + "<button class=\"" + css + "\" type=\"submit\">" + label + "</button></form>";
    }

    private static String typeOptions(String selected) {
        StringBuilder options = new StringBuilder();
        for (String type : List.of("expense", "refund", "loan", "loan_payment")) {
            options.append("<option value=\"").append(type).append("\"")
                    .append(type.equals(selected) ? " selected" : "")
                    .append(">").append(Html.escape(type)).append("</option>");
        }
        return options.toString();
    }

    private static String label(Draft draft) {
        if (draft.merchant() != null) {
            return draft.merchant();
        }
        if (draft.person() != null) {
            return draft.person();
        }
        return draft.note() == null ? "Unlabeled" : draft.note();
    }

    private static String label(LedgerEntry entry) {
        if (entry.merchant() != null) {
            return entry.merchant();
        }
        if (entry.person() != null) {
            return entry.person();
        }
        return entry.note() == null ? "Unlabeled" : entry.note();
    }

    private static void securityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set(
                "Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    private static void methodNotAllowed(HttpExchange exchange) throws IOException {
        text(exchange, 405, "Method not allowed\n", "text/plain");
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
    }

    private static void respondError(HttpExchange exchange, int status, String message) {
        try {
            text(exchange, status, Html.page(
                    "Error",
                    "<div class=\"card\"><h1>Request failed</h1><p class=\"error\">"
                            + Html.escape(message) + "</p><a class=\"button secondary\" href=\"/\">Back</a></div>"),
                    "text/html");
        } catch (IOException ignored) {
            exchange.close();
        }
    }

    private static void text(
            HttpExchange exchange, int status, String content, String contentType)
            throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        if (server != null) {
            server.stop(2);
            server = null;
        }
        requests.shutdownNow();
        LOGGER.info("Dashboard stopped");
    }
}
