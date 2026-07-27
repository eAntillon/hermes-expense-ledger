package dev.eantillon.expenseledger.integration;

import dev.eantillon.expenseledger.config.AppConfig;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class HermesConfigurator {

    private static final Load YAML_READER = new Load(LoadSettings.builder()
            .setLabel("Hermes config")
            .setAllowDuplicateKeys(false)
            .setDefaultMap(size -> new LinkedHashMap<>(size))
            .build());
    private static final Dump YAML_WRITER = new Dump(DumpSettings.builder()
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .setIndent(2)
            .setIndicatorIndent(0)
            .setIndentWithIndicator(true)
            .setSplitLines(false)
            .build());

    private HermesConfigurator() {
    }

    public static void main(String[] args) {
        if (args.length != 0) {
            System.err.println("Usage: HermesConfigurator");
            System.exit(2);
        }
        try {
            AppConfig config = AppConfig.fromEnvironment();
            Path path = Optional.ofNullable(System.getenv("HERMES_CONFIG_PATH"))
                    .filter(value -> !value.isBlank())
                    .map(Path::of)
                    .orElseGet(() -> Path.of(
                            System.getProperty("user.home"), ".hermes", "config.yaml"));
            configure(path, config.requireDiscordChannelId());
            System.out.println("Hermes channel binding is configured.");
        } catch (RuntimeException | IOException exception) {
            System.err.println("Hermes configuration failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    static void configure(Path path, String channelId) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Hermes config file was not found: " + path);
        }
        String source = Files.readString(path);
        Object loaded = source.isBlank() ? null : YAML_READER.loadFromString(source);
        Map<Object, Object> root = rootMap(loaded);
        Map<Object, Object> platforms = childMap(root, "platforms");
        Map<Object, Object> discord = childMap(platforms, "discord");

        List<Object> bindings = normalizeBindings(discord.get("channel_skill_bindings"));
        bindings.removeIf(binding -> channelId.equals(bindingId(binding)));
        Map<String, Object> expenseBinding = new LinkedHashMap<>();
        expenseBinding.put("id", channelId);
        expenseBinding.put("skills", List.of("manage-expenses"));
        bindings.add(expenseBinding);
        discord.put("channel_skill_bindings", bindings);
        discord.put(
                "free_response_channels",
                addFreeResponseChannel(discord.get("free_response_channels"), channelId));

        writeAtomically(path, YAML_WRITER.dumpToString(root));
    }

    private static Map<Object, Object> rootMap(Object loaded) {
        if (loaded == null) {
            return new LinkedHashMap<>();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Hermes config root must be a YAML mapping");
        }
        return copyMap(map);
    }

    private static Map<Object, Object> childMap(Map<Object, Object> parent, String key) {
        Object existing = parent.get(key);
        Map<Object, Object> child;
        if (existing == null) {
            child = new LinkedHashMap<>();
        } else if (existing instanceof Map<?, ?> map) {
            child = copyMap(map);
        } else {
            throw new IllegalArgumentException("Hermes config key " + key + " must be a mapping");
        }
        parent.put(key, child);
        return child;
    }

    private static Map<Object, Object> copyMap(Map<?, ?> source) {
        Map<Object, Object> copy = new LinkedHashMap<>();
        source.forEach(copy::put);
        return copy;
    }

    private static List<Object> normalizeBindings(Object existing) {
        Object value = existing;
        if (value instanceof String serialized && !serialized.isBlank()) {
            value = YAML_READER.loadFromString(serialized);
        }
        if (value == null) {
            return new ArrayList<>();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "platforms.discord.channel_skill_bindings must be a list");
        }
        return new ArrayList<>(list);
    }

    private static String bindingId(Object binding) {
        if (binding instanceof Map<?, ?> map) {
            Object id = map.get("id");
            return id == null ? null : id.toString();
        }
        return null;
    }

    private static Object addFreeResponseChannel(Object existing, String channelId) {
        if (existing == null || existing instanceof String) {
            Set<String> channels = new LinkedHashSet<>();
            if (existing instanceof String text) {
                for (String part : text.split(",")) {
                    if (!part.isBlank()) {
                        channels.add(part.trim());
                    }
                }
            }
            channels.add(channelId);
            return String.join(",", channels);
        }
        if (existing instanceof List<?> list) {
            List<Object> channels = new ArrayList<>(list);
            if (channels.stream().noneMatch(channel -> channelId.equals(channel.toString()))) {
                channels.add(channelId);
            }
            return channels;
        }
        throw new IllegalArgumentException(
                "platforms.discord.free_response_channels must be a string or list");
    }

    private static void writeAtomically(Path path, String yaml) throws IOException {
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(path);
        } catch (UnsupportedOperationException exception) {
            permissions = PosixFilePermissions.fromString("rw-------");
        }

        Path backup = path.resolveSibling(path.getFileName() + ".expense-ledger.bak");
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        setPermissions(backup, permissions);

        Path temporary = Files.createTempFile(path.getParent(), ".config.yaml.", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, yaml);
            setPermissions(temporary, permissions);
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions)
            throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // POSIX permissions are unavailable on this filesystem.
        }
    }
}
