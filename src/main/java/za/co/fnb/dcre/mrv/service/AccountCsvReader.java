package za.co.fnb.dcre.mrv.service;

import org.springframework.stereotype.Component;
import za.co.fnb.dcre.mrv.domain.AccountReferenceLoadFailure;
import za.co.fnb.dcre.mrv.domain.AccountReferenceManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies {@code account.csv} against the manifest and parses it into ordered
 * column-to-value maps. Checksum first, then header, then row count: each gate is a
 * different way for the artifact to be the wrong artifact.
 *
 * <p>An empty cell means ABSENT, per the artifact contract, and is carried as an empty
 * string here for the projection to reject or ignore per column.
 */
@Component
public class AccountCsvReader {

    /** The 18 columns, in order. A header that differs at all is a different artifact shape. */
    static final List<String> HEADER = List.of(
            "shape", "account_number", "product_code", "status", "account_type_code", "app_no",
            "acc_type", "branch_code", "balance", "max_credit_limit", "cancel_reason", "country_id",
            "edr_ind", "pre_ind", "process_status", "status_reason", "ucn", "client_id");

    public List<Map<String, String>> read(final Path file, final AccountReferenceManifest manifest) {
        final byte[] bytes = bytesOf(file);
        requireChecksum(file, bytes, manifest.checksum());
        final List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        requireHeader(file, lines);
        final List<Map<String, String>> rows = parse(file, lines);
        if (rows.size() != manifest.rowCount()) {
            throw new AccountReferenceLoadFailure(
                    "%s holds %d data rows but the manifest declares row.count=%d"
                            .formatted(file, rows.size(), manifest.rowCount()));
        }
        return rows;
    }

    private static byte[] bytesOf(final Path file) {
        if (!Files.isRegularFile(file)) {
            throw new AccountReferenceLoadFailure("account reference csv is absent: " + file);
        }
        try {
            return Files.readAllBytes(file);
        } catch (final IOException e) {
            throw new AccountReferenceLoadFailure("account reference csv is unreadable: " + file, e);
        }
    }

    private static void requireChecksum(final Path file, final byte[] bytes, final String expected) {
        final String actual = sha256(bytes);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new AccountReferenceLoadFailure(
                    "%s checksum mismatch: manifest declares %s, the bytes hash to %s"
                            .formatted(file, expected, actual));
        }
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException e) {
            throw new AccountReferenceLoadFailure("SHA-256 is unavailable on this JVM", e);
        }
    }

    private static void requireHeader(final Path file, final List<String> lines) {
        final List<String> actual = lines.isEmpty() ? List.of() : split(lines.getFirst());
        if (!HEADER.equals(actual)) {
            throw new AccountReferenceLoadFailure(
                    "%s header is not the 18 artifact columns in order: expected %s, found %s"
                            .formatted(file, HEADER, actual));
        }
    }

    private static List<Map<String, String>> parse(final Path file, final List<String> lines) {
        final List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            final List<String> values = split(lines.get(i));
            if (values.size() != HEADER.size()) {
                throw new AccountReferenceLoadFailure(
                        "%s line %d has %d cells, not the %d the header declares"
                                .formatted(file, i + 1, values.size(), HEADER.size()));
            }
            final Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < HEADER.size(); c++) {
                row.put(HEADER.get(c), values.get(c));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * The limit of -1 is load-bearing: a MANDATES row is four populated cells followed by
     * fourteen empty ones, and {@code split(",")} discards every trailing empty field, so
     * the default form reads an 18-column row as 5 columns and the shape check passes for
     * the wrong reason on exactly the rows this loader materialises.
     */
    private static List<String> split(final String line) {
        return List.of(line.split(",", -1));
    }
}
