package za.co.fnb.dcre.mrv.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;
import za.co.fnb.dcre.mrv.ManEffectiveStatusTables;
import za.co.fnb.dcre.mrv.ManTestTables;
import za.co.fnb.dcre.mrv.data.repo.ManReferenceSnapshotDao;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCRUM-91 re-homes the 1:1-live invariant to MRV admission. A view cannot reject a
 * write, so this belongs on the request leg where R-20 always put it. Live means
 * state IN ('PDNG','ACCP','SUSPENDED'); terminal rows (RJCT/CANC/EXPIRED) free the
 * contract.
 *
 * <p>There is NO DB backstop (A-73): the partial unique index is unbuildable now that
 * the mandate table is gone, so MRV is the sole enforcement point and these tests are
 * the guard. Every case drives the REAL job so the rejection is proven end to end,
 * through the durable man_validation_log outcome MIR turns into a NACK reason and the
 * spine_state transition that stops the row reaching MAF.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class OneLiveMandatePerContractIT {

    private static final String LIVE_REASON = "CONTRACT_HAS_LIVE_MANDATE";
    private static final String DEBTOR = "6200000021";
    private static final String CREDITOR = "7300000001";

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
    }

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
    }

    @Autowired
    Job mrvJob;
    @Autowired
    JobOperator jobOperator;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ManReferenceSnapshotDao snapshotDao;

    @BeforeEach
    void seedReference() {
        ManTestTables.createSpine(jdbc);
        ManEffectiveStatusTables.createViewStack(jdbc);
        ManTestTables.seedAccount(jdbc, DEBTOR, "CHQ");
    }

    /** Registers one CREATE for {@code contract} and returns the verdict MRV durably recorded. */
    private String register(final String client, final String ref, final String contract) throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, client, 1);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", ref, contract, DEBTOR, CREDITOR, false);
        jobOperator.start(mrvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true).toJobParameters());
        return jdbc.queryForObject("SELECT outcome FROM man_validation_log WHERE arrival_id=? AND sequence=1",
                String.class, arrival);
    }

    /** A prior arrival whose mandate reached the PBSR leg with the given status. */
    private void seedRepliedMandate(final String client, final String ref, final String contract,
                                    final String expiry, final String status) {
        final UUID prior = UUID.randomUUID();
        final String reqId = "MREQ-" + ref;
        ManEffectiveStatusTables.seedPriorRegistration(jdbc, prior, client, ref, contract,
                DEBTOR, CREDITOR, expiry, reqId, "VALIDATED");
        ManEffectiveStatusTables.seedPbsr(jdbc, reqId, ref, status, null);
    }

    private String stateOf(final String ref) {
        return jdbc.queryForObject("SELECT state FROM mandate_effective_status WHERE mandate_ref=?",
                String.class, ref);
    }

    @Test
    void aSecondRegistrationForALiveContractIsRejected() throws Exception {
        seedRepliedMandate("CL01", "MND40", "CONTRACTA", "20991231", "ACCP");
        assertEquals("ACCP", stateOf("MND40"));

        assertEquals(LIVE_REASON, register("CL01", "MND41", "CONTRACTA"));
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT spine_state FROM mandate_request_entry WHERE mandate_ref=?", String.class, "MND41"),
                "the rejected registration must not proceed to MAF");
    }

    @Test
    void aPendingPredecessorStillBlocks() throws Exception {
        final UUID prior = UUID.randomUUID();
        ManEffectiveStatusTables.seedPriorRegistration(jdbc, prior, "CL01", "MND60", "CONTRACTP",
                DEBTOR, CREDITOR, "20991231", "MREQ-MND60", "VALIDATED");
        ManEffectiveStatusTables.seedVerdict(jdbc, prior, 1, "PASS");
        assertEquals("PDNG", stateOf("MND60"), "validated, awaiting debtor authentication");

        assertEquals(LIVE_REASON, register("CL01", "MND61", "CONTRACTP"));
    }

    @Test
    void aSuspendedPredecessorStillBlocks() throws Exception {
        seedRepliedMandate("CL01", "MND50", "CONTRACTG", "20991231", "ACCP");
        ManEffectiveStatusTables.seedOverride(jdbc, "MND50", "SUSPENDED", "MS03", "COLLECTION_FAILURE");
        assertEquals("SUSPENDED", stateOf("MND50"));

        assertEquals(LIVE_REASON, register("CL01", "MND51", "CONTRACTG"));
    }

    @Test
    void aDifferentContractIsAdmitted() throws Exception {
        seedRepliedMandate("CL01", "MND42", "CONTRACTB", "20991231", "ACCP");

        assertEquals("PASS", register("CL01", "MND43", "CONTRACTC"));
    }

    @Test
    void theSameContractUnderADifferentClientIsAdmitted() throws Exception {
        seedRepliedMandate("CL01", "MND44", "CONTRACTD", "20991231", "ACCP");

        assertEquals("PASS", register("CL02", "MND45", "CONTRACTD"));
    }

    @Test
    void aTerminalPredecessorFreesTheContract() throws Exception {
        seedRepliedMandate("CL01", "MND46", "CONTRACTE", "20991231", "CANC");
        assertEquals("CANC", stateOf("MND46"));

        assertEquals("PASS", register("CL01", "MND47", "CONTRACTE"));
    }

    @Test
    void aRejectedPredecessorFreesTheContract() throws Exception {
        seedRepliedMandate("CL01", "MND56", "CONTRACTJ", "20991231", "RJCT");
        assertEquals("RJCT", stateOf("MND56"));

        assertEquals("PASS", register("CL01", "MND57", "CONTRACTJ"));
    }

    @Test
    void anExpiredPredecessorFreesTheContract() throws Exception {
        seedRepliedMandate("CL01", "MND48", "CONTRACTF", "20260101", "ACCP");
        assertEquals("EXPIRED", stateOf("MND48"), "expiry is a predicate, no writer ran");

        assertEquals("PASS", register("CL01", "MND49", "CONTRACTF"));
    }

    @Test
    void amendingTheSameMandateRefIsNotABlockedTwin() throws Exception {
        seedRepliedMandate("CL01", "MND52", "CONTRACTH", "20991231", "ACCP");

        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "CL01", 1);
        ManTestTables.insertEntry(jdbc, arrival, 1, "AMEND", "MND52", "CONTRACTH", DEBTOR, CREDITOR, false);
        jobOperator.start(mrvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true).toJobParameters());

        assertEquals("PASS", jdbc.queryForObject(
                "SELECT outcome FROM man_validation_log WHERE arrival_id=? AND sequence=1", String.class, arrival),
                "an amendment of the SAME mandate_ref is the live mandate itself, not a twin");
    }

    /**
     * The MRR B1a intra-file duplicate loser: mndt_req_id NULL, dup_in_file true, MRV
     * verdict FAIL_DUPLICATE_REF. mandate_effective_status maps a non-PASS MRV verdict at
     * stage_rank 1 to RJCT, so it is terminal and must NOT hold the contract hostage.
     * Nothing here may key or filter on mndt_req_id: doing so drops this row entirely.
     */
    @Test
    void anMrvRejectedDuplicateIsTerminalAndDoesNotBlockTheContract() throws Exception {
        final UUID prior = UUID.randomUUID();
        ManEffectiveStatusTables.seedPriorRegistration(jdbc, prior, "CL01", "MND58", "CONTRACTK",
                DEBTOR, CREDITOR, "20991231", null, "REJECTED");
        ManEffectiveStatusTables.seedVerdict(jdbc, prior, 1, "FAIL_DUPLICATE_REF");
        assertEquals("RJCT", stateOf("MND58"), "a request-leg rejection is terminal");

        assertEquals("PASS", register("CL01", "MND59", "CONTRACTK"));
    }

    /**
     * The snapshot cannot see the arrival under validation, so two rows of ONE file claiming
     * the same contract would both be admitted and the invariant would be broken by a single
     * file. Later occurrence loses, the same shape MRR's B1a duplicate rule uses.
     */
    @Test
    void twoRegistrationsForOneContractInOneFileAdmitOnlyTheFirst() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "CL01", 2);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MND70", "CONTRACTM", DEBTOR, CREDITOR, false);
        ManTestTables.insertEntry(jdbc, arrival, 2, "CREATE", "MND71", "CONTRACTM", DEBTOR, CREDITOR, false);
        jobOperator.start(mrvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true).toJobParameters());

        assertEquals("PASS", jdbc.queryForObject(
                "SELECT outcome FROM man_validation_log WHERE arrival_id=? AND sequence=1", String.class, arrival));
        assertEquals(LIVE_REASON, jdbc.queryForObject(
                "SELECT outcome FROM man_validation_log WHERE arrival_id=? AND sequence=2", String.class, arrival));
    }

    /**
     * R-23/A-29: a blank contract_ref keys on (client, debtor_account, creditor_account).
     * Asserted at the DAO because MRV's contract-format stage (A-61) rejects a blank
     * contract_ref before the admissibility stage is reached, so the fallback is not
     * observable end to end today. See the report note on that conflict.
     */
    @Test
    void aBlankContractRefFallsBackToTheAccountTriple() {
        final UUID prior = UUID.randomUUID();
        ManEffectiveStatusTables.seedPriorRegistration(jdbc, prior, "CL01", "MND53", "",
                "DEB1", "CRED1", "20991231", "MREQ-MND53", "VALIDATED");
        ManEffectiveStatusTables.seedPbsr(jdbc, "MREQ-MND53", "MND53", "ACCP", null);
        final String asOf = snapshotDao.snapshotTimestamp();

        assertEquals("MND53", snapshotDao.findLiveByAccounts(asOf, "CL01", "DEB1", "CRED1").orElseThrow());
        assertTrue(snapshotDao.findLiveByAccounts(asOf, "CL01", "DEB2", "CRED1").isEmpty(),
                "a different debtor account is a different contract identity");
        assertTrue(snapshotDao.findLiveByAccounts(asOf, "CL02", "DEB1", "CRED1").isEmpty(),
                "the client dimension is part of the key");
    }
}
