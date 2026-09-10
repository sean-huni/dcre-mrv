package za.co.fnb.dcre.mrv.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds synthetic account reference artifacts on disk for the refusal paths.
 *
 * <p>The checksum is COMPUTED from the bytes it writes unless a test overrides it, so a
 * fixture is always internally consistent except where a test deliberately breaks one thing.
 * A fixture that got its own checksum wrong would make every other case fail at gate 5 and
 * pass for the wrong reason.
 *
 * <p>These synthetic artifacts exist only for cases the committed artifact cannot express (a
 * broken checksum, a wrong schema version, an empty projection). The happy path is proven
 * against the REAL committed artifact, because hand-built fixtures on both sides of a seam
 * are a drift class green tests cannot see.
 */
final class AccountArtifactFixture {

    private static final String HEADER = String.join(",", AccountCsvReader.HEADER);

    private AccountArtifactFixture() {
    }

    /** A MANDATES-shape line: four populated cells then the fourteen the shape does not carry. */
    static String mandatesRow(final String accountNumber, final String productCode,
                              final String status, final String accountTypeCode) {
        return String.join(",", "MANDATES", accountNumber, productCode, status, accountTypeCode)
                + ",".repeat(13);
    }

    /** A COLLECTIONS-shape line, present so "no rows of MY shape" is a real artifact, not an empty file. */
    static String collectionsRow(final String accountNumber) {
        return String.join(",", "COLLECTIONS", accountNumber, "FNBRF", "AAUT", "", "2590451916851902005",
                "CACC", "250205", "100000.00", "", "", "1", "false", "false", "ACTIVE", "", "100000000205", "2");
    }

    /**
     * @param root      the reference root; the dataset directory is created under it
     * @param version   the dataset version, used as the directory name AND the manifest key
     * @param dataRows  csv data lines, header excluded
     * @param overrides manifest keys to override, e.g. a wrong checksum or schema version
     * @return the dataset directory
     */
    static Path write(final Path root, final String version, final List<String> dataRows,
                      final Map<String, String> overrides) {
        try {
            final Path directory = Files.createDirectories(root.resolve(version));
            final String csv = HEADER + "\n" + String.join("\n", dataRows) + "\n";
            final byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
            Files.write(directory.resolve("account.csv"), bytes);
            Files.writeString(directory.resolve("manifest.properties"), manifest(version, dataRows, bytes, overrides));
            return directory;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String manifest(final String version, final List<String> dataRows, final byte[] bytes,
                                   final Map<String, String> overrides) {
        final Map<String, String> keys = new LinkedHashMap<>();
        keys.put("dataset.version", version);
        keys.put("schema.version", "1");
        keys.put("source.id", "fixture:mrv-test");
        keys.put("effective.ts", "2026-08-09T00:00:00Z");
        keys.put("publication.ts", "2026-08-09T00:00:00Z");
        keys.put("row.count", String.valueOf(dataRows.size()));
        keys.put("checksum.sha256", sha256(bytes));
        keys.putAll(overrides);
        return keys.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce("", (a, b) -> a + b + "\n");
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
