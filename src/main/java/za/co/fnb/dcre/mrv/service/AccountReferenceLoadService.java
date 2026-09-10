package za.co.fnb.dcre.mrv.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mrv.config.AccountReferenceProperties;
import za.co.fnb.dcre.mrv.domain.AccountReferenceLoadFailure;
import za.co.fnb.dcre.mrv.domain.AccountReferenceManifest;
import za.co.fnb.dcre.mrv.domain.AccountReferenceRow;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Materialises the versioned account reference artifact into MRV's OWN
 * {@code dcre_man.account}, in the contract's order: resolve, manifest, dataset version,
 * schema version, checksum, parse, freshness, project, apply.
 *
 * <p>THE LOADER DOES NOT OWN ACCOUNT REFERENCE DATA IN GENERAL, and the natural reading of
 * this class's name is the wrong one. {@code account_type} is a CLOSED VOCABULARY of five
 * codes seeded by Liquibase ({@code 000-man-core-bootstrap.xml}), of which SAV is the only
 * one disallowing mandates. It is not part of the artifact, no run of this service reads,
 * writes, clears or reseeds it, and a future artifact that appears to carry account types
 * still does not authorise this loader to touch it.
 */
@Service
public class AccountReferenceLoadService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountReferenceLoadService.class);

    private final AccountReferenceProperties properties;
    private final AccountArtifactReader reader;
    private final AccountMaterialiser materialiser;

    public AccountReferenceLoadService(final AccountReferenceProperties properties,
                                       final AccountArtifactReader reader,
                                       final AccountMaterialiser materialiser) {
        this.properties = properties;
        this.reader = reader;
        this.materialiser = materialiser;
    }

    /**
     * @param jobExecutionId the Spring Batch execution this load ran under, or null when the
     *                       service is driven directly
     * @return how many rows this context materialised
     */
    public int load(final Long jobExecutionId) {
        final Path directory = properties.datasetDirectory();
        final AccountReferenceManifest manifest = reader.readManifest(directory);
        requireExpectedDataset(manifest);
        requireSupportedSchema(manifest);
        final List<Map<String, String>> artifactRows = reader.readRows(directory, manifest);
        checkFreshness(manifest);
        final List<AccountReferenceRow> projected =
                AccountMandatesProjection.project(artifactRows, manifest.datasetVersion());
        materialiser.apply(manifest, projected, jobExecutionId);
        LOG.info("account reference dataset {} applied: {} of {} artifact rows materialised into"
                + " dcre_man.account from {}", manifest.datasetVersion(), projected.size(),
                manifest.rowCount(), directory);
        return projected.size();
    }

    private void requireExpectedDataset(final AccountReferenceManifest manifest) {
        if (!properties.datasetVersion().equals(manifest.datasetVersion())) {
            throw new AccountReferenceLoadFailure(
                    "dataset.version mismatch: the manifest declares %s, this service expects %s"
                            .formatted(manifest.datasetVersion(), properties.datasetVersion()));
        }
    }

    private void requireSupportedSchema(final AccountReferenceManifest manifest) {
        if (manifest.schemaVersion() != AccountReferenceManifest.SUPPORTED_SCHEMA) {
            throw new AccountReferenceLoadFailure(
                    "schema.version mismatch: the artifact declares %d, this loader supports %d"
                            .formatted(manifest.schemaVersion(), AccountReferenceManifest.SUPPORTED_SCHEMA));
        }
    }

    /**
     * A-4: the freshness contract does not exist yet, so this gate ships wired and INERT and
     * says so on every run rather than quietly doing nothing. Configuring a Duration arms it
     * with no code change; no number is invented here.
     */
    private void checkFreshness(final AccountReferenceManifest manifest) {
        final Duration maxAge = properties.maxAge();
        if (maxAge == null) {
            LOG.info("account reference freshness check is INERT pending A-4:"
                            + " dcre.mrv.reference.account.max-age is unset, so publication.ts {} is not checked",
                    manifest.publicationTs());
            return;
        }
        final Duration age = Duration.between(manifest.publicationTs(), Instant.now());
        if (age.compareTo(maxAge) > 0) {
            throw new AccountReferenceLoadFailure(
                    "account reference dataset %s is stale: publication.ts %s is %s old, past the configured max-age %s"
                            .formatted(manifest.datasetVersion(), manifest.publicationTs(), age, maxAge));
        }
    }
}
