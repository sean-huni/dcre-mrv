package za.co.fnb.dcre.mrv.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.fnb.dcre.mrv.ManReadSideSchema;
import za.co.fnb.dcre.mrv.ManTestTables;
import za.co.fnb.dcre.mrv.data.repo.AccountReferenceNotMaterialisedException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE THREE STATES, each proven by running it. They must never be conflated:
 *
 * <ol>
 *   <li>artifact absent or invalid: a technical loader failure naming the artifact
 *       ({@link AccountReferenceRefusalIT}),</li>
 *   <li>master populated, this account not on it: FAIL_ACCOUNT_NOT_FOUND, a business verdict,
 *       and the job SUCCEEDS,</li>
 *   <li>master empty because nothing ever loaded: a TECHNICAL failure naming the
 *       materialisation step, and NOT one business rejection anywhere.</li>
 * </ol>
 *
 * <p>State 3 is the defect this suite exists for. Before the guard, a missed deployment step
 * produced a full file of FAIL_ACCOUNT_NOT_FOUND that a client reads as their own data being
 * wrong, and nothing in the run said otherwise. The assertion that carries the whole point is
 * therefore not "the job failed" but "ZERO validation-log rows carry FAIL_ACCOUNT_NOT_FOUND".
 *
 * <p>Every empty-state case asserts its emptiness FIRST, as a positive control. Without it a
 * pass could mean the artifact happened to be loaded by a sibling case and the run never
 * reached the state under test.
 */
class AccountReferenceMaterialisationIT extends AbstractAccountReferenceIT {

    private static final String NOT_ON_THE_MASTER = "6299999999";
    private static final String ON_THE_MASTER = "62001482970090167";

    @Autowired
    Job mrvJob;
    @Autowired
    JobOperator jobOperator;

    /** The spine relations MRR owns, plus a verdict log this class can reason about globally. */
    @BeforeEach
    void freshSpine() {
        ManReadSideSchema.apply(jdbc);
        jdbc.update("DELETE FROM man_validation_log WHERE 1 = 1");
        jdbc.update("DELETE FROM mandate_request_entry WHERE 1 = 1");
        jdbc.update("DELETE FROM mandate_request_header WHERE 1 = 1");
    }

    /**
     * STATE 3. No loader has run, so the account master and its ledger are both empty, and the
     * run must halt naming the step that did not happen rather than rejecting every row.
     */
    @Test
    void anUnmaterialisedMasterFailsTechnicallyAndRejectsNothing() throws Exception {
        assertThat(accountRowCount())
                .as("positive control: an artifact loaded by another case would make this test vacuous")
                .isZero();
        assertThat(loadLedgerRowCount()).as("positive control: nothing ever loaded").isZero();
        final UUID arrival = arrivalCarrying(ON_THE_MASTER);

        final JobExecution run = run(arrival);

        // Asserted FIRST because it is the load-bearing one: with the guard removed this is
        // the line that goes red, and a status assertion placed ahead of it would fire first
        // and hide which property is actually being proven.
        assertThat(outcomeCount("FAIL_ACCOUNT_NOT_FOUND"))
                .as("THE POINT: not one row may be rejected for an account nothing could have held")
                .isZero();
        assertThat(verdictCount()).as("and no verdict at all was written for this run").isZero();
        assertThat(run.getStatus()).as("a missed deployment step is a technical failure")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(failureMessages(run))
                .anySatisfy(message -> assertThat(message)
                        .contains("account_reference_load holds no row")
                        .contains("NOT a business rejection")
                        .contains("scripts/cutover-v1.sh")
                        .contains("scripts/env-reset.sh")
                        .contains("DCRE_MRV_JOB_NAME=mrvAccountReferenceLoadJob"));
        assertThat(failureTypes(run)).contains(AccountReferenceNotMaterialisedException.class.getName());
    }

    /**
     * STATE 3, second shape. A ledger row exists but applied nothing, so the master is empty
     * for a recorded reason. Still technical, and the message names the dataset so an operator
     * can tell which load did it.
     */
    @Test
    void aLedgerRowThatAppliedNoRowsFailsTechnicallyToo() throws Exception {
        ManTestTables.seedAccountReferenceLoad(jdbc, "2026.08.09-001", 0);
        assertThat(accountRowCount()).as("positive control: the master really is empty").isZero();
        final UUID arrival = arrivalCarrying(ON_THE_MASTER);

        final JobExecution run = run(arrival);

        assertThat(outcomeCount("FAIL_ACCOUNT_NOT_FOUND"))
                .as("an empty master is still not evidence about any individual account")
                .isZero();
        assertThat(run.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failureMessages(run))
                .anySatisfy(message -> assertThat(message)
                        .contains("applied_row_count 0")
                        .contains("2026.08.09-001")
                        .contains("DCRE_MRV_JOB_NAME=mrvAccountReferenceLoadJob"));
    }

    /**
     * STATE 2, the verdict that must SURVIVE the guard. The real artifact is materialised
     * first, so the master genuinely holds 100 accounts and this debtor genuinely is not one
     * of them. That is a business rejection, and the job completes.
     */
    @Test
    void anAccountMissingFromALoadedMasterIsStillABusinessRejection() throws Exception {
        materialiseTheRealArtifact();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM account WHERE account_number = ?", Integer.class, NOT_ON_THE_MASTER))
                .as("positive control: the debtor really is absent from a populated master")
                .isZero();
        final UUID arrival = arrivalCarrying(NOT_ON_THE_MASTER);

        final JobExecution run = run(arrival);

        assertThat(run.getStatus()).as("a business rejection is not a technical failure")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(run.getExitStatus().getExitCode()).isEqualTo("BUSINESS_FILE_REJECTED");
        assertThat(outcome(arrival, 1)).isEqualTo("FAIL_ACCOUNT_NOT_FOUND");
    }

    /** A loaded master admits an account that IS on it: the guard refuses nothing it should not. */
    @Test
    void anAccountPresentOnALoadedMasterPasses() throws Exception {
        materialiseTheRealArtifact();
        final UUID arrival = arrivalCarrying(ON_THE_MASTER);

        final JobExecution run = run(arrival);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(outcome(arrival, 1)).isEqualTo("PASS");
    }

    /**
     * WHAT THE RUN CONSUMED, recorded where it can still be answered afterwards. A run whose
     * reference data cannot be named later is not auditable, and the ledger alone cannot say
     * it: a reload between the run and the question would rewrite the answer.
     */
    @Test
    void theRunRecordsTheDatasetItJudgedAgainstInItsBatchMetadata() throws Exception {
        materialiseTheRealArtifact();
        final UUID arrival = arrivalCarrying(ON_THE_MASTER);

        final JobExecution run = run(arrival);

        assertThat(run.getExecutionContext().getString("accountDatasetVersion"))
                .isEqualTo("2026.08.09-001");
    }

    private void materialiseTheRealArtifact() {
        serviceFor(ARTIFACT_SOURCE, referenceProperties.datasetVersion(), null).load(null);
        assertThat(accountRowCount()).as("the real artifact materialised its projection").isEqualTo(100);
    }

    /** One CREATE instruction for the given debtor account, on its own arrival. */
    private UUID arrivalCarrying(final String debtorAccount) {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC01", 1);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-" + arrival.toString().substring(0, 8),
                "CTRMAT1", debtorAccount, false);
        return arrival;
    }

    private JobExecution run(final UUID arrival) throws Exception {
        return jobOperator.start(mrvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true).toJobParameters());
    }

    private static List<String> failureMessages(final JobExecution run) {
        return run.getAllFailureExceptions().stream().map(Throwable::getMessage).toList();
    }

    private static List<String> failureTypes(final JobExecution run) {
        return run.getAllFailureExceptions().stream().map(t -> t.getClass().getName()).toList();
    }

    private String outcome(final UUID arrival, final int sequence) {
        return jdbc.queryForObject(
                "SELECT outcome FROM man_validation_log WHERE arrival_id=? AND sequence=?",
                String.class, arrival, sequence);
    }

    private int outcomeCount(final String outcome) {
        return jdbc.queryForObject("SELECT count(*) FROM man_validation_log WHERE outcome = ?",
                Integer.class, outcome);
    }

    private int verdictCount() {
        return jdbc.queryForObject("SELECT count(*) FROM man_validation_log", Integer.class);
    }

    private int loadLedgerRowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM account_reference_load", Integer.class);
    }
}
