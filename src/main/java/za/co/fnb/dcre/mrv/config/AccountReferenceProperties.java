package za.co.fnb.dcre.mrv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Where the account reference artifact is and which version this service demands.
 *
 * @param root           directory holding one sub-directory per dataset version
 * @param datasetVersion the version MRV expects; the manifest must EQUAL it. There is no
 *                       "newest directory wins" behaviour, because that is a fail-open: a
 *                       stray directory would silently become the reference data.
 * @param maxAge         freshness limit for {@code publication.ts}. NULL BY DESIGN and with
 *                       no default anywhere: the freshness contract is A-4's to define, so
 *                       the gate ships wired and inert, logging its inertness on every run,
 *                       and arms itself the day a real number is configured.
 */
@ConfigurationProperties("dcre.mrv.reference.account")
public record AccountReferenceProperties(Path root, String datasetVersion, Duration maxAge) {

    /** The artifact directory this run must read, or fail on. */
    public Path datasetDirectory() {
        return root.resolve(datasetVersion);
    }
}
