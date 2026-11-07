package day.alacraft.alamodlist.i18n;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alamodlist.api.ApiErrorCode;
import day.alacraft.alamodlist.config.ClientConfig;
import day.alacraft.alamodlist.flow.Category;
import day.alacraft.alamodlist.flow.ErrorText;
import day.alacraft.alamodlist.config.KnobConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The English catalogue against the code, read from disk - no game needed.
 *
 * <p>A lang key is a string literal: the compiler does not check it, so a typo reaches the player as
 * the raw key ({@code message.alamodlist.pnog}). These checks are what catches it. They read the source
 * tree through paths relative to this module, which is Gradle's working directory for tests.
 *
 * <p>Only what needs the code lives here. That every locale has exactly the keys and placeholders of
 * {@code en_us} is the job of {@code tools/lang_check.py} alone - one rule, one oracle.
 */
class LangCatalogTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final Path ASSETS = REPO.resolve("common-mc/src/main/resources/assets/alamodlist");
    private static final Path LANG = ASSETS.resolve("lang");
    private static final List<Path> SOURCES = List.of(
            REPO.resolve("common/src/main/java"),
            REPO.resolve("common-mc/src/main/java"),
            REPO.resolve("fabric/src/main/java"),
            REPO.resolve("neoforge/src/main/java"));

    /** A full lang key written as a Java string literal. Prefixes are the ones the mod uses. */
    private static final Pattern KEY_LITERAL = Pattern.compile(
            "\"((?:block|item|itemGroup|key|message|config|container|screen|command|toast)\\.alamodlist\\.[A-Za-z0-9_.]+)\"");

    @Test
    void everyKeyUsedInCodeExists() {
        Set<String> english = load("en_us").keySet();
        Set<String> missing = new TreeSet<>(keysInCode());
        missing.removeAll(english);
        assertTrue(missing.isEmpty(), "used in code but missing from en_us: " + missing);
    }

    @Test
    void everyClientSettingHasALabelAndATooltip() {
        Set<String> english = load("en_us").keySet();
        for (KnobConfig.KnobView knob : ClientConfig.config().knobs()) {
            assertTrue(english.contains("config.alamodlist." + knob.key()), "no label for " + knob.key());
            assertTrue(english.contains("config.alamodlist." + knob.key() + ".tooltip"), "no tooltip for " + knob.key());
        }
    }

    /**
     * The sentences the player reads for a failure and the names of the categories are chosen by code, not spelled
     * where they are used, so the check that reads literals cannot see them going missing: walk the enums instead.
     */
    @Test
    void everyErrorCodeAndCategoryHasItsSentences() {
        Set<String> english = load("en_us").keySet();
        for (ApiErrorCode code : ApiErrorCode.values()) {
            for (ErrorText.Action action : ErrorText.Action.values()) {
                String key = ErrorText.key(code, action);
                assertTrue(english.contains(key), code + "/" + action + " has no sentence: " + key);
            }
        }
        for (Category category : Category.values()) {
            assertTrue(english.contains(category.nameKey()), category + " has no name");
            assertTrue(english.contains(category.summaryKey()), category + " has no summary");
            if (category.reason().key() != null) {
                assertTrue(english.contains(category.reason().key()), category + " has no reason");
            }
        }
        for (Category.Reason reason : Category.Reason.values()) {
            if (reason.key() != null) {
                assertTrue(english.contains(reason.key()), reason + " has no sentence");
            }
        }
    }

    /** A block item takes the block's key (useBlockDescriptionPrefix); any other item needs an item key. */
    @Test
    void everyBlockAndItemHasAName() {
        Set<String> english = load("en_us").keySet();
        Set<String> blocks = names(ASSETS.resolve("blockstates"));
        for (String block : blocks) {
            assertTrue(english.contains("block.alamodlist." + block), "no name for block " + block);
        }
        for (String item : names(ASSETS.resolve("items"))) {
            String key = blocks.contains(item) ? "block.alamodlist." + item : "item.alamodlist." + item;
            assertTrue(english.contains(key), "no name for item " + item + " (" + key + ")");
        }
    }

    /**
     * The scanner is not blind: a check whose empty result means "no problems" must prove it can find
     * something. A broken pattern would otherwise pass every test above by finding nothing.
     */
    @Test
    void theScannerFindsKeys() {
        Matcher matcher = KEY_LITERAL.matcher("show(Component.translatable(\"message.alamodlist.pong\"));");
        assertTrue(matcher.find());
        assertEquals("message.alamodlist.pong", matcher.group(1));
        Set<String> found = keysInCode();
        // The settings screen's title is spelled in code in every profile (the demo keys differ by profile).
        assertTrue(found.contains("config.alamodlist.title"), "the settings title key must be found in the sources");
        assertTrue(found.size() >= 3, "suspiciously few keys found in code: " + found);
        // Keys written in the pure module are found too: the preview's sentences live there.
        assertTrue(found.contains("message.alamodlist.error.bad_address"), "keys of the common module are not scanned");
        assertFalse(load("en_us").isEmpty(), "en_us.json is empty in " + LANG);
    }

    // ---------------------------------------------------------------------------------------------

    private static Map<String, String> load(String locale) {
        Path file = LANG.resolve(locale + ".json");
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> entries = new TreeMap<>();
            json.entrySet().forEach(entry -> entries.put(entry.getKey(), entry.getValue().getAsString()));
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> keysInCode() {
        Set<String> keys = new TreeSet<>();
        for (Path root : SOURCES) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    Matcher matcher = KEY_LITERAL.matcher(Files.readString(file, StandardCharsets.UTF_8));
                    while (matcher.find()) {
                        keys.add(matcher.group(1));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return keys;
    }

    /** File names without ".json" in a directory; a directory that does not exist (a mod with no blocks) holds none. */
    private static Set<String> names(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new TreeSet<>();
        }
        try (Stream<Path> files = Files.list(directory)) {
            Set<String> names = new TreeSet<>();
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .forEach(name -> names.add(name.substring(0, name.length() - ".json".length())));
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
