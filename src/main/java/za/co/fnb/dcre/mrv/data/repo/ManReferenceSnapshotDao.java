package za.co.fnb.dcre.mrv.data.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.mrv.service.VerdictChain.Account;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * CTV F51 pattern: one consistent AS OF SYSTEM TIME snapshot of the dcre_man
 * reference stores, so every row of an arrival is judged against the same MVCC
 * view even under a concurrent account/mandate mutation. The HLC is captured once
 * at the header step ({@link #snapshotTimestamp()}); the account, account_type and
 * known-ref reads all pin to it. The as-of reads run on their OWN pooled connection
 * (CockroachDB requires AS OF SYSTEM TIME to be the first statement of a read-only
 * transaction, so it cannot share MRV's verdict-writing transaction).
 */
@Component
public class ManReferenceSnapshotDao {

    /** cluster_logical_timestamp() is a plain HLC decimal; guard before inlining. */
    private static final Pattern HLC_DECIMAL = Pattern.compile("\\d+(\\.\\d+)?");

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public ManReferenceSnapshotDao(final JdbcTemplate jdbc, final DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /** HLC captured at the header step; every reference read pins to this snapshot. */
    public String snapshotTimestamp() {
        return jdbc.queryForObject("SELECT cluster_logical_timestamp()::STRING", String.class);
    }

    /** account master rows for the debtor accounts in this arrival (account_number -> Account). */
    public Map<String, Account> accountsByNumber(final String asOf, final Collection<String> accountNumbers) {
        final Map<String, Account> byNumber = new HashMap<>();
        if (accountNumbers.isEmpty()) {
            return byNumber;
        }
        final String sql = "SELECT account_number, account_type_code "
                + "FROM account AS OF SYSTEM TIME '" + requireHlc(asOf) + "' "
                + "WHERE account_number IN (" + placeholders(accountNumbers.size()) + ")";
        query(sql, accountNumbers, rs ->
                byNumber.put(rs.getString("account_number"),
                        new Account(rs.getString("account_number"), rs.getString("account_type_code"))));
        return byNumber;
    }

    /** account_type reference (code -> mandates_allowed); tiny table, read whole. */
    public Map<String, Boolean> mandatesAllowedByType(final String asOf) {
        final Map<String, Boolean> allowed = new HashMap<>();
        final String sql = "SELECT code, mandates_allowed FROM account_type AS OF SYSTEM TIME '"
                + requireHlc(asOf) + "'";
        query(sql, Set.of(), rs -> allowed.put(rs.getString("code"), rs.getBoolean("mandates_allowed")));
        return allowed;
    }

    /**
     * The subset of {@code refs} that are KNOWN prior registrations: decided in the
     * derived {@code mandate_effective_status} view, or on the spine as a non-rejected
     * CREATE from ANOTHER arrival. The current arrival is excluded so a CREATE never
     * collides with its own RECEIVED row (intra-file duplicates are the chain's stage 2).
     *
     * <p>SCRUM-91: the first arm read the MSR-written {@code mandate} projection, which
     * this wave deletes. Its replacement is spine-DERIVED, so unlike the projection it
     * also contains the arrival currently under validation, and a bare table swap would
     * make every CREATE collide with itself. The {@code state <> 'PDNG'} guard is what
     * restores the projection's meaning: a row still awaiting a request-leg verdict or a
     * debtor authentication reads PDNG, and the arrival under validation is always in
     * that set (its verdicts are written after this read). Prior-arrival mandates still
     * sitting PDNG are covered by the spine arm, so the union is unchanged.
     */
    public Set<String> knownRefs(final String asOf, final Collection<String> refs, final UUID currentArrival) {
        final Set<String> known = new HashSet<>();
        if (refs.isEmpty()) {
            return known;
        }
        final String decided = "SELECT DISTINCT mandate_ref FROM mandate_effective_status AS OF SYSTEM TIME '"
                + requireHlc(asOf) + "' WHERE mandate_ref IN (" + placeholders(refs.size()) + ") "
                + "AND state <> 'PDNG'";
        query(decided, refs, rs -> known.add(rs.getString("mandate_ref")));

        final String priorSpine = "SELECT DISTINCT mandate_ref FROM mandate_request_entry AS OF SYSTEM TIME '"
                + requireHlc(asOf) + "' WHERE mandate_ref IN (" + placeholders(refs.size()) + ") "
                + "AND action_code = 'CREATE' AND spine_state <> 'REJECTED' AND arrival_id <> ?";
        queryWithArrival(priorSpine, refs, currentArrival, rs -> known.add(rs.getString("mandate_ref")));
        return known;
    }

    /**
     * The live mandate already bound to {@code (client, contractRef)}, or empty when the
     * contract is free. Live is the effective state, never a row count: PDNG and ACCP are
     * in flight and SUSPENDED is a collection-failure hold that still occupies the
     * contract, while RJCT, CANC and EXPIRED are terminal and release it. Keyed on
     * {@code (client, contract_ref)} and filtered on {@code state} only: {@code mndt_req_id}
     * is NULLABLE (MRR's B1a rule lands an intra-file duplicate loser with a NULL one), so
     * keying or filtering on it silently drops rows.
     */
    public Optional<String> findLiveByContract(final String asOf, final String client, final String contractRef) {
        return findLive("AND contract_ref = ?", asOf, List.of(client, contractRef));
    }

    /** R-23/A-29 fallback: a blank contract_ref keys on the account triple instead. */
    public Optional<String> findLiveByAccounts(final String asOf, final String client,
                                               final String debtorAccount, final String creditorAccount) {
        return findLive("AND (contract_ref IS NULL OR contract_ref = '') "
                + "AND debtor_account = ? AND creditor_account = ?",
                asOf, List.of(client, debtorAccount, creditorAccount));
    }

    /**
     * {@code raw_status <> 'PDNG'} is what excludes the arrival currently under validation.
     * The view is spine-derived, so this arrival's own rows are in it, and their raw_status
     * is the bottom of the COALESCE ladder ('PDNG') precisely because MRV has not verdicted
     * them yet: a registration occupies a contract only once MRV has admitted it. Without
     * this every row would see its own file's siblings as live twins. Note this is the
     * RAW status, not the derived state: a prior mandate awaiting debtor authentication
     * carries raw_status 'MRV_PASS' and state 'PDNG', and must still block.
     *
     * <p>ORDER BY makes the pick deterministic when a contract somehow carries more than
     * one live mandate: the reported blocker must not change between reads.
     */
    private Optional<String> findLive(final String keyPredicate, final String asOf, final List<String> params) {
        final List<String> live = new ArrayList<>();
        final String sql = "SELECT mandate_ref FROM mandate_effective_status AS OF SYSTEM TIME '"
                + requireHlc(asOf) + "' WHERE client = ? " + keyPredicate
                + " AND state IN ('PDNG','ACCP','SUSPENDED') AND raw_status <> 'PDNG' "
                + "ORDER BY mandate_ref";
        query(sql, params, rs -> live.add(rs.getString("mandate_ref")));
        return live.stream().findFirst();
    }

    private interface RowConsumer {
        void accept(ResultSet rs) throws SQLException;
    }

    private void query(final String sql, final Collection<String> params, final RowConsumer consumer) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            for (final String param : params) {
                ps.setString(i++, param);
            }
            consume(ps, consumer);
        } catch (final SQLException e) {
            throw new IllegalStateException("as-of reference read failed", e);
        }
    }

    private void queryWithArrival(final String sql, final Collection<String> params,
                                  final UUID currentArrival, final RowConsumer consumer) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            for (final String param : params) {
                ps.setString(i++, param);
            }
            ps.setObject(i, currentArrival);
            consume(ps, consumer);
        } catch (final SQLException e) {
            throw new IllegalStateException("as-of reference read failed", e);
        }
    }

    private static void consume(final PreparedStatement ps, final RowConsumer consumer) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                consumer.accept(rs);
            }
        }
    }

    private static String placeholders(final int count) {
        return IntStream.range(0, count).mapToObj(n -> "?").collect(Collectors.joining(","));
    }

    private static String requireHlc(final String asOf) {
        if (asOf == null || !HLC_DECIMAL.matcher(asOf).matches()) {
            throw new IllegalArgumentException("invalid as-of timestamp: " + asOf);
        }
        return asOf;
    }
}
