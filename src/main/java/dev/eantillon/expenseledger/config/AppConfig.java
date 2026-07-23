package dev.eantillon.expenseledger.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public record AppConfig(
        Currency baseCurrency,
        ZoneId timezone,
        Optional<String> discordChannelId,
        Set<String> allowedDiscordUserIds,
        Path databasePath,
        Path backupDirectory,
        Path logDirectory,
        String webBind,
        int webPort,
        Optional<String> webAccessToken,
        int backupHour,
        int dailyRetention,
        int monthlyRetention) {

    private static final Pattern DISCORD_ID = Pattern.compile("[0-9]{15,22}");

    public AppConfig {
        Objects.requireNonNull(baseCurrency, "baseCurrency");
        Objects.requireNonNull(timezone, "timezone");
        discordChannelId = Objects.requireNonNull(discordChannelId, "discordChannelId");
        allowedDiscordUserIds = Set.copyOf(allowedDiscordUserIds);
        Objects.requireNonNull(databasePath, "databasePath");
        Objects.requireNonNull(backupDirectory, "backupDirectory");
        Objects.requireNonNull(logDirectory, "logDirectory");
        Objects.requireNonNull(webBind, "webBind");
        webAccessToken = Objects.requireNonNull(webAccessToken, "webAccessToken");
    }

    public static AppConfig fromEnvironment() {
        return from(System.getenv());
    }

    static AppConfig from(Map<String, String> env) {
        String home = System.getProperty("user.home");
        Currency currency = parseCurrency(value(env, "EXPENSE_BASE_CURRENCY", "GTQ"));
        ZoneId zone = parseZone(value(env, "EXPENSE_TIMEZONE", "America/Guatemala"));
        Optional<String> channel = optional(env.get("EXPENSE_DISCORD_CHANNEL_ID"));
        channel.ifPresent(value -> validateDiscordId("EXPENSE_DISCORD_CHANNEL_ID", value));

        Set<String> users = new LinkedHashSet<>();
        optional(env.get("EXPENSE_DISCORD_ALLOWED_USER_IDS")).ifPresent(value ->
                Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(part -> !part.isEmpty())
                        .forEach(part -> {
                            validateDiscordId("EXPENSE_DISCORD_ALLOWED_USER_IDS", part);
                            users.add(part);
                        }));

        Path database = path(env, "EXPENSE_DB_PATH",
                home + "/.local/share/hermes-expense-ledger/data/ledger.db", home);
        Path backups = path(env, "EXPENSE_BACKUP_DIR",
                home + "/backups/hermes-expense-ledger", home);
        Path logs = path(env, "EXPENSE_LOG_DIR",
                home + "/.local/state/hermes-expense-ledger/logs", home);

        int port = integer(env, "EXPENSE_WEB_PORT", 8787, 1, 65535);
        int hour = integer(env, "EXPENSE_BACKUP_HOUR", 3, 0, 23);
        int daily = integer(env, "EXPENSE_BACKUP_DAILY_RETENTION", 30, 1, 3650);
        int monthly = integer(env, "EXPENSE_BACKUP_MONTHLY_RETENTION", 12, 0, 1200);

        return new AppConfig(
                currency,
                zone,
                channel,
                users,
                database,
                backups,
                logs,
                value(env, "EXPENSE_WEB_BIND", "127.0.0.1").trim(),
                port,
                optional(env.get("EXPENSE_WEB_ACCESS_TOKEN")),
                hour,
                daily,
                monthly);
    }

    public String requireDiscordChannelId() {
        return discordChannelId.orElseThrow(() ->
                new ConfigurationException("EXPENSE_DISCORD_CHANNEL_ID is required for MCP mode"));
    }

    public String requireWebAccessToken() {
        String token = webAccessToken.orElseThrow(() ->
                new ConfigurationException("EXPENSE_WEB_ACCESS_TOKEN is required for dashboard mode"));
        if (token.length() < 32 || token.equals("replace-with-a-generated-token")) {
            throw new ConfigurationException(
                    "EXPENSE_WEB_ACCESS_TOKEN must be a generated secret of at least 32 characters");
        }
        return token;
    }

    public void validateDashboardBoundary() {
        requireWebAccessToken();
        try {
            if (!InetAddress.getByName(webBind).isLoopbackAddress()) {
                throw new ConfigurationException(
                        "EXPENSE_WEB_BIND must resolve to a loopback address; use Tailscale Serve for private access");
            }
        } catch (UnknownHostException exception) {
            throw new ConfigurationException("EXPENSE_WEB_BIND cannot be resolved: " + webBind, exception);
        }
    }

    private static Currency parseCurrency(String raw) {
        try {
            Currency currency = Currency.getInstance(raw.trim().toUpperCase());
            if (currency.getDefaultFractionDigits() < 0) {
                throw new ConfigurationException("EXPENSE_BASE_CURRENCY has no supported minor-unit definition");
            }
            return currency;
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException("EXPENSE_BASE_CURRENCY must be a valid ISO 4217 code", exception);
        }
    }

    private static ZoneId parseZone(String raw) {
        try {
            return ZoneId.of(raw.trim());
        } catch (DateTimeException exception) {
            throw new ConfigurationException("EXPENSE_TIMEZONE must be a valid IANA timezone", exception);
        }
    }

    private static Path path(Map<String, String> env, String key, String fallback, String home) {
        String raw = value(env, key, fallback).trim();
        if (raw.equals("~")) {
            raw = home;
        } else if (raw.startsWith("~/")) {
            raw = home + raw.substring(1);
        } else if (raw.startsWith("~")) {
            throw new ConfigurationException(key + " supports only ~ or ~/ path expansion");
        }
        Path result = Path.of(raw).normalize();
        if (!result.isAbsolute()) {
            throw new ConfigurationException(key + " must be an absolute path");
        }
        return result;
    }

    private static int integer(
            Map<String, String> env, String key, int fallback, int minimum, int maximum) {
        String raw = value(env, key, Integer.toString(fallback)).trim();
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < minimum || parsed > maximum) {
                throw new ConfigurationException(key + " must be between " + minimum + " and " + maximum);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ConfigurationException(key + " must be an integer", exception);
        }
    }

    private static Optional<String> optional(String value) {
        return Optional.ofNullable(value).map(String::trim).filter(part -> !part.isEmpty());
    }

    private static String value(Map<String, String> env, String key, String fallback) {
        return Optional.ofNullable(env.get(key)).filter(value -> !value.isBlank()).orElse(fallback);
    }

    private static void validateDiscordId(String key, String value) {
        if (!DISCORD_ID.matcher(value).matches()) {
            throw new ConfigurationException(key + " must contain Discord snowflake IDs");
        }
    }
}
