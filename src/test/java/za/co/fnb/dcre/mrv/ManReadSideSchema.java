package za.co.fnb.dcre.mrv;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stands up the MRG/MRR-owned relations MRV reads (the request spine, the response-leg
 * tables, the pick views, {@code mandate_effective_status} and the per-MANDATE collapse
 * {@code mandate_current_status}) by RUNNING MRG's own changesets, not by re-typing them.
 *
 * <p>The fixture that this replaces hand-copied those definitions into Java text blocks and
 * drifted from the owner without any test noticing: it had lost {@code action_code}, had the
 * wrong {@code mnd_pbsr_pick} ordering, and never created {@code mandate_current_status} at
 * all, which is precisely why MRV's 1:1-live check could read the wrong grain unchallenged.
 * The changelog copies live in {@code src/test/resources/mrg-changelog/} and are byte-compared
 * against MRG's sources by {@link MrgChangelogDriftTest}.
 *
 * <p>Applied once per DataSource: each IT class owns a CockroachDB container, and Liquibase's
 * own history check is skipped rather than re-taken on every {@code @BeforeEach}.
 */
public final class ManReadSideSchema {

    /** Fixture history, separate from mrv_databasechangelog: MRV does not own these relations. */
    private static final String HISTORY = "mrgfixture_databasechangelog";

    private static final Set<DataSource> APPLIED = ConcurrentHashMap.newKeySet();

    private ManReadSideSchema() {
    }

    public static void apply(final JdbcTemplate jdbc) {
        final DataSource dataSource = Objects.requireNonNull(jdbc.getDataSource(),
                "no DataSource under the test JdbcTemplate");
        if (APPLIED.add(dataSource)) {
            migrate(dataSource);
        }
    }

    private static void migrate(final DataSource dataSource) {
        final SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:mrg-changelog/mrg-read-side.xml");
        liquibase.setDatabaseChangeLogTable(HISTORY);
        liquibase.setDatabaseChangeLogLockTable(HISTORY + "lock");
        try {
            liquibase.afterPropertiesSet();
        } catch (final Exception e) {
            throw new IllegalStateException("MRG read-side fixture migration failed", e);
        }
    }
}
