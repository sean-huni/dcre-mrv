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

    /** The two facts the materialisation guard needs from the newest account reference load. */
    public record AccountReferenceLoad(String datasetVersion, int appliedRowCount) {
    }

    /**
     * The newest {@code account_reference_load} row, or empty when the loader has NEVER run.
     * The ledger row is written in the same transaction as the account rows it materialised,
     * so it states what the master currently holds; counting {@code account} and inferring
     * would answer a different, race-prone question.
     *
     * <p>Read AS OF the SAME captured HLC and on the SAME own-connection path as every other
     * reference read here, deliberately: a technical read failure then raises this DAO's
     * {@code as-of reference read failed} rather than degrading to an empty Optional, which
     * the guard would report as "never loaded" and blame on a deployment step that did run.
     */
    public Optional<AccountReferenceLoad> latestAccountReferenceLoad(final String asOf) {
        final List<AccountReferenceLoad> newest = new ArrayList<>();
        final String sql = "SELECT dataset_version, applied_row_count FROM account_reference_load "
                + "AS OF SYSTEM TIME '" + requireHlc(asOf) + "' ORDER BY created_at DESC LIMIT 1";
        query(sql, Set.of(), rs -> newest.add(new AccountReferenceLoad(
                rs.getString("dataset_version"), rs.getInt("applied_row_count"))));
        return newest.stream().findFirst();
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
     * contract, while RJCT, CANC and EXPIRED are terminal and release it.
     */
    public Optional<String> findLiveByContract(final String asOf, final String client,
                                               final String contractRef, final UUID currentArrival) {
        return findLive("AND e.contract_ref = ?", asOf, List.of(client, contractRef), currentArrival);
    }

    /** R-23/A-29 fallback: a blank contract_ref keys on the account triple instead. */
    public Optional<String> findLiveByAccounts(final String asOf, final String client,
                                               final String debtorAccount, final String creditorAccount,
                                               final UUID currentArrival) {
        return findLive("AND (e.contract_ref IS NULL OR e.contract_ref = '') "
                + "AND e.debtor_account = ? AND e.creditor_account = ?",
                asOf, List.of(client, debtorAccount, creditorAccount), currentArrival);
    }

    /**
     * "Is this contract already taken" is a per-MANDATE question, so LIVENESS is asked of the
     * per-MANDATE collapse {@code mandate_current_status} (mrg 007), the same relation
     * {@code man_ctv_view} and the parity gate read. The per-INSTRUCTION
     * {@code mandate_effective_status} it read before answers a different question and diverges
     * on every terminal shape that lands on a LATER instruction: an accepted CANCEL, an MD07
     * termination, a rejected AMEND. All three leave the CREATE instruction's own row ACCP for
     * ever, so the contract stayed occupied by a mandate CTV had already stopped collecting for
     * and the client could never re-register it (SCRUM-91 defect; MRG had already fixed the
     * identical grain bug in its own suspension sweep).
     *
     * <p>CONTRACT IDENTITY is resolved on the SPINE, not on the collapse's own identity
     * columns, because 007 publishes {@code max(contract_ref)} / {@code max(debtor_account)}
     * over a mandate's instructions. max() is lexicographic, not latest, so a mandate whose
     * instructions disagree would advertise a contract it may not hold and hide one it does.
     * Keying on the spine blocks a contract that ANY instruction of the mandate binds it to,
     * the fail-closed direction, and it also lets CockroachDB filter the collapse on
     * {@code mandate_ref}, its GROUP BY key, instead of on aggregate outputs no predicate can
     * be pushed below.
     *
     * <p>SELF-EXCLUSION can no longer key on raw_status: the collapse publishes no raw status
     * and must not grow one for MRV's benefit. It is {@code e.arrival_id <> ?} instead, which
     * excludes the mandates this arrival INTRODUCES and nothing else. Excluding every mandate
     * the arrival merely mentions is wrong and was caught red: the replace-a-mandate file
     * carries the replacement CREATE alongside the CANCEL of the mandate it replaces, and
     * hiding that predecessor admits the twin onto an occupied contract, then NACKs the file's
     * own CANCEL as the twin. Rows of THIS arrival claiming one contract are the caller's
     * {@code admitted} map, not this read.
     *
     * <p>CockroachDB applies AS OF SYSTEM TIME to the whole statement, so the spine keying and
     * the liveness rows come from the same instant.
     *
     * <p>Deliberate consequence of dropping the raw_status test: a PRIOR arrival that MRV has
     * not verdicted yet reads PDNG here and now BLOCKS, where before it was invisible. That is
     * the fail-closed direction, and it is the right one with no DB backstop (A-73): a
     * transient false CONTRACT_HAS_LIVE_MANDATE is a resendable NACK, while two mandates
     * admitted onto one contract by concurrent arrivals is unrecoverable double collection.
     *
     * <p>ORDER BY makes the pick deterministic when a contract somehow carries more than
     * one live mandate: the reported blocker must not change between reads.
     */
    private Optional<String> findLive(final String keyPredicate, final String asOf,
                                      final List<String> params, final UUID currentArrival) {
        final List<String> live = new ArrayList<>();
        final String sql = "SELECT mandate_ref FROM mandate_current_status AS OF SYSTEM TIME '"
                + requireHlc(asOf) + "' WHERE state IN ('PDNG','ACCP','SUSPENDED') "
                + "AND mandate_ref IN (SELECT e.mandate_ref FROM mandate_request_entry e "
                + "JOIN mandate_request_header h ON h.arrival_id = e.arrival_id "
                + "WHERE h.client_token = ? " + keyPredicate + " AND e.arrival_id <> ?) "
                + "ORDER BY mandate_ref";
        queryWithArrival(sql, params, currentArrival, rs -> live.add(rs.getString("mandate_ref")));
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
