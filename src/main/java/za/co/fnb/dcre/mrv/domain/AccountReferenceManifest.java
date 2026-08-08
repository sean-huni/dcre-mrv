package za.co.fnb.dcre.mrv.domain;

import java.time.Instant;

/**
 * The seven mandatory keys of an account reference artifact's {@code manifest.properties}.
 * All seven are required: a manifest missing any one of them is not a manifest, and the
 * loader refuses rather than defaulting, because every default here would be a guess about
 * provenance the artifact deliberately does not claim.
 *
 * @param datasetVersion the artifact's identity, which the consumer declares and matches
 * @param schemaVersion  the artifact's shape version, matched against {@link #SUPPORTED_SCHEMA}
 * @param sourceId       what the rows were derived from, recorded verbatim, never interpreted
 * @param effectiveTs    the publication instant, NOT a business effective date (A-4 open)
 * @param publicationTs  what the max-age gate measures against once A-4 arms it
 * @param rowCount       data rows in {@code account.csv}, header excluded, whole artifact
 * @param checksum       sha256 over the bytes of {@code account.csv} exactly as committed
 */
public record AccountReferenceManifest(String datasetVersion, int schemaVersion, String sourceId,
                                       Instant effectiveTs, Instant publicationTs, int rowCount,
                                       String checksum) {

    /** The only artifact shape this loader can read; a mismatch names both numbers and fails. */
    public static final int SUPPORTED_SCHEMA = 1;
}
