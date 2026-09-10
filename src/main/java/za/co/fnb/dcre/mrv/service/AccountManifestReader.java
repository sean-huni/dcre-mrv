package za.co.fnb.dcre.mrv.service;

import org.springframework.stereotype.Component;
import za.co.fnb.dcre.mrv.domain.AccountReferenceLoadFailure;
import za.co.fnb.dcre.mrv.domain.AccountReferenceManifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Properties;

/**
 * Reads the artifact's {@code manifest.properties}. It is a DATA file read with
 * {@link Properties}, not Spring configuration, which is why a {@code .properties} file is
 * correct here while Spring config in this service stays yml-only.
 *
 * <p>All seven keys are mandatory and none has a default. A missing key fails naming the
 * key, so the operator learns which one rather than that "the manifest was bad".
 */
@Component
public class AccountManifestReader {

    public AccountReferenceManifest read(final Path file) {
        final Properties properties = load(file);
        return new AccountReferenceManifest(
                require(properties, "dataset.version", file),
                integer(properties, "schema.version", file),
                require(properties, "source.id", file),
                instant(properties, "effective.ts", file),
                instant(properties, "publication.ts", file),
                integer(properties, "row.count", file),
                require(properties, "checksum.sha256", file));
    }

    private static Properties load(final Path file) {
        if (!Files.isRegularFile(file)) {
            throw new AccountReferenceLoadFailure("account reference manifest is absent: " + file);
        }
        final Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (final IOException e) {
            throw new AccountReferenceLoadFailure("account reference manifest is unreadable: " + file, e);
        }
        return properties;
    }

    private static String require(final Properties properties, final String key, final Path file) {
        final String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new AccountReferenceLoadFailure(
                    "account reference manifest %s is missing the mandatory key '%s'".formatted(file, key));
        }
        return value.trim();
    }

    private static int integer(final Properties properties, final String key, final Path file) {
        final String value = require(properties, key, file);
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            throw new AccountReferenceLoadFailure(
                    "account reference manifest %s key '%s' is not an integer: '%s'".formatted(file, key, value), e);
        }
    }

    private static Instant instant(final Properties properties, final String key, final Path file) {
        final String value = require(properties, key, file);
        try {
            return Instant.parse(value);
        } catch (final DateTimeParseException e) {
            throw new AccountReferenceLoadFailure(
                    "account reference manifest %s key '%s' is not an ISO-8601 instant: '%s'"
                            .formatted(file, key, value), e);
        }
    }
}
