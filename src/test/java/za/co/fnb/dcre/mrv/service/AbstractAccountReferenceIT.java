package za.co.fnb.dcre.mrv.service;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;
import za.co.fnb.dcre.mrv.config.AccountReferenceProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * One CockroachDB and ONE Spring context shared by the account reference suites: the
 * refusal cases and the applied case ask different questions of the same schema, and a
 * second container per class buys nothing but minutes and a fuller Docker VM.
 *
 * <p>{@code dcre.exchange-root} is redirected into a temp directory. The committed default
 * resolves into the infra repository, and a test that writes a seam file there would mutate
 * a checked-out working tree as a side effect of running the suite.
 *
 * <p>{@code dcre.mrv.reference.account.root} is redirected to {@link #ARTIFACT_SOURCE} for the
 * same reason the exchange root is redirected, and for one more. The committed default now
 * derives from the exchange root, so it names the STAGED copy that infra's deploy-time step
 * writes onto the exchange volume, and no such copy exists in a working tree. What DOES exist
 * in git is the artifact's SOURCE under {@code infra/dcre-infra/fixtures/reference/account},
 * which is what these suites parse. Reading the file the fleet actually ships is the whole
 * value of this base class: hand-built fixtures on both sides of a seam are a drift class
 * green tests cannot see.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
abstract class AbstractAccountReferenceIT {

    /**
     * The committed SOURCE of the account reference artifact, six hops from this module
     * directory to the repository root. Infra validates this tree and stages a copy into the
     * exchange root at deploy time; the app only ever reads the staged copy.
     */
    static final Path ARTIFACT_SOURCE =
            Path.of("../../../../../../infra/dcre-infra/fixtures/reference/account");

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static final Path EXCHANGE = tempDirectory("mrv-account-reference-seam");

    static {
        CRDB.start();
    }

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected AccountArtifactReader reader;
    @Autowired
    protected AccountMaterialiser materialiser;
    /** Bound from the committed yml except for the root, which is redirected above. */
    @Autowired
    protected AccountReferenceProperties referenceProperties;

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.exchange-root", EXCHANGE::toString);
        registry.add("dcre.mrv.reference.account.root", ARTIFACT_SOURCE::toString);
    }

    /** Every case starts from an empty projection so a row found afterwards is one this run put there. */
    @BeforeEach
    void clearMaterialisedProjection() {
        jdbc.update("DELETE FROM account WHERE 1 = 1");
        jdbc.update("DELETE FROM account_reference_load WHERE 1 = 1");
    }

    /**
     * A service bound to the given configuration, over the REAL reader and the REAL
     * materialiser. Only the configuration varies, so a refusal proven here is a refusal of
     * the shipped code path and not of a stub.
     */
    protected AccountReferenceLoadService serviceFor(final Path root, final String version,
                                                     final Duration maxAge) {
        return new AccountReferenceLoadService(
                new AccountReferenceProperties(root, version, maxAge), reader, materialiser);
    }

    protected int accountRowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM account", Integer.class);
    }

    protected static Path tempDirectory(final String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
