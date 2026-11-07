package day.alacraft.alamodlist.hash;

import day.alacraft.alamodlist.source.ModKind;
import day.alacraft.alamodlist.source.RawMod;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hasher against real mod jars of two modpacks, one per Minecraft line. The tables in
 * {@code src/test/resources/reference} hold, per jar, the SHA-1 an independent source recorded (what the mod
 * site stores for the file, or a hash from another implementation), the size and the bare file name - no paths.
 *
 * <p>The jars themselves are not in the repository. Where they are is read from the environment variable
 * {@code ALAMODLIST_REFERENCE_JARS_26_2} / {@code _26_3}, or from the untracked file {@code .reference-jars} at
 * the root of the repository ({@code 26.2=<folder>} and {@code 26.3=<folder>}). Without a folder a test does
 * not run, and says so in its skip message: a skipped test is not a passed one.
 */
class ReferenceJarsTest {

    @TempDir
    Path folder;

    private record Row(String sha1, long size, String fileName) {
    }

    @Test
    void everyJarOfTheModpackForMinecraft262HashesToWhatTheSiteRecorded() throws IOException {
        verify("26.2");
    }

    @Test
    void everyJarOfTheServerForMinecraft263HashesToTheIndependentReference() throws IOException {
        verify("26.3");
    }

    private void verify(String line) throws IOException {
        Path jars = locate(line);
        Assumptions.assumeTrue(jars != null && Files.isDirectory(jars),
                "NOT RUN: no folder with the reference jars of " + line + " (set ALAMODLIST_REFERENCE_JARS_"
                        + line.replace('.', '_') + " or .reference-jars); this does not count as a pass");
        List<Row> rows = table(line);

        List<RawMod> mods = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Row row : rows) {
            Path jar = jars.resolve(row.fileName());
            if (Files.isRegularFile(jar)) {
                mods.add(new RawMod("m" + mods.size(), null, null, ModKind.MOD, null, null, jar, null, List.of()));
            } else {
                missing.add(row.fileName());
            }
        }
        assertTrue(missing.isEmpty(), "jars of the table that are not in the folder: " + missing);

        Path cacheFile = folder.resolve("hash-cache.json");
        ModHashing.Outcome cold = ModHashing.run(mods, HashCache.open(cacheFile), false, ModHashing.DEFAULT_THREADS,
                new Progress(), new CancelToken());
        ModHashing.Outcome warm = ModHashing.run(mods, HashCache.open(cacheFile), false, ModHashing.DEFAULT_THREADS,
                new Progress(), new CancelToken());

        int matched = 0;
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ModHashing.FileFacts facts = cold.facts().get(ModHashing.keyOf(jars.resolve(row.fileName())));
            if (facts != null && facts.hashes().sha1().equals(row.sha1()) && facts.size() == row.size()) {
                matched++;
            } else {
                wrong.add(row.fileName());
            }
        }

        double megabytes = cold.stats().bytesHashed() / 1_048_576.0;
        double coldSeconds = cold.stats().nanos() / 1e9;
        double warmSeconds = warm.stats().nanos() / 1e9;
        String report = String.format(Locale.ROOT,
                "line %s: %d of %d jars match the reference; read %.0f MiB in %.2f s with %d threads (%.0f MiB/s); "
                        + "second snapshot from the cache: %d hits, %d bytes read, %.3f s%n",
                line, matched, rows.size(), megabytes, coldSeconds, ModHashing.DEFAULT_THREADS,
                megabytes / coldSeconds, warm.stats().cacheHits(), warm.stats().bytesHashed(), warmSeconds);
        Path out = Path.of("build", "reference-jars-" + line + ".txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);

        assertEquals(rows.size(), matched, "jars that do not match: " + wrong);
        assertEquals(0, cold.stats().failed());
        assertEquals(rows.size(), warm.stats().cacheHits(), "the second snapshot should be served from the cache");
        assertEquals(0, warm.stats().bytesHashed());
        assertTrue(warm.stats().nanos() < cold.stats().nanos(), report);
        assertEquals(cold.facts(), warm.facts());
    }

    private static List<Row> table(String line) throws IOException {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = ReferenceJarsTest.class.getResourceAsStream("/reference/sha1-" + line + ".tsv")) {
            assertTrue(in != null, "the reference table of " + line + " is missing");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String text;
            while ((text = reader.readLine()) != null) {
                String[] parts = text.split("\t", 3);
                rows.add(new Row(parts[0], Long.parseLong(parts[1]), parts[2]));
            }
        }
        return rows;
    }

    private static Path locate(String line) throws IOException {
        String fromEnvironment = System.getenv("ALAMODLIST_REFERENCE_JARS_" + line.replace('.', '_'));
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return Path.of(fromEnvironment);
        }
        Path file = Path.of("..", ".reference-jars");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String value = properties.getProperty(line);
        return value == null || value.isBlank() ? null : file.resolveSibling(value.trim()).normalize();
    }
}
