package dev.eantillon.expenseledger.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesConfiguratorTest {

    @TempDir
    Path temporary;

    @Test
    void repairsSerializedBindingAndPreservesOtherChannels() throws Exception {
        Path config = temporary.resolve("config.yaml");
        Files.writeString(config, """
                model:
                  default: gpt-5.6-luna
                platforms:
                  discord:
                    free_response_channels: "111111111111111111"
                    channel_skill_bindings: >-
                      [{"id":"111111111111111111","skills":["other-skill"]}]
                """);

        HermesConfigurator.configure(config, "1529608465209753732");
        HermesConfigurator.configure(config, "1529608465209753732");

        Map<?, ?> root = map(load(config));
        Map<?, ?> platforms = map(root.get("platforms"));
        Map<?, ?> discord = map(platforms.get("discord"));
        List<?> bindings = list(discord.get("channel_skill_bindings"));
        assertEquals(2, bindings.size());
        assertEquals(
                List.of("111111111111111111", "1529608465209753732"),
                bindings.stream().map(HermesConfiguratorTest::bindingId).toList());
        assertEquals(
                "111111111111111111,1529608465209753732",
                discord.get("free_response_channels"));
        assertTrue(Files.isRegularFile(
                config.resolveSibling("config.yaml.expense-ledger.bak")));
    }

    private static Object load(Path path) throws Exception {
        return new Load(LoadSettings.builder().build()).loadFromString(Files.readString(path));
    }

    private static Map<?, ?> map(Object value) {
        return (Map<?, ?>) value;
    }

    private static List<?> list(Object value) {
        return (List<?>) value;
    }

    private static String bindingId(Object value) {
        return map(value).get("id").toString();
    }
}
