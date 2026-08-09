package za.co.fnb.dcre.mrv.data.repo;

/**
 * The account master was never materialised into {@code dcre_man.account}, so this run has no
 * reference data to judge anything against.
 *
 * <p>THIS IS NOT A {@code MandateOutcome}, and that is the whole point of the type. A
 * MandateOutcome is a statement about ONE instruction: this debtor account is not on the
 * master, therefore this row is refused. When nothing ever loaded, that statement is false for
 * every row in the file, and emitting it per row converts one missed deployment step into a
 * file's worth of business rejections that a client will read as their data being wrong. The
 * three states must stay distinguishable:
 *
 * <ol>
 *   <li>artifact absent or invalid: {@link AccountReferenceLoadFailure} from the loader,</li>
 *   <li>master populated, no row matches this account:
 *       {@code MandateOutcome.FAIL_ACCOUNT_NOT_FOUND}, a genuine business verdict,</li>
 *   <li>master empty because nothing ever loaded: THIS type, a technical failure that halts
 *       the run and names the step that did not happen.</li>
 * </ol>
 *
 * <p>The authority is the {@code account_reference_load} ledger, not a row count over
 * {@code account}: the ledger row is written in the SAME transaction as the rows it
 * materialised, so it is a statement about the table's current contents rather than a guess
 * from a count that a concurrent load could be part-way through changing.
 */
public class AccountReferenceNotMaterialisedException extends RuntimeException {

    /** Named so an operator reads the fix, not a diagnosis they then have to translate. */
    private static final String MATERIALISATION_STEP =
            "infra stages the artifact into the exchange root"
                    + " (scripts/cutover-v1.sh / scripts/env-reset.sh),"
                    + " then the loader run DCRE_MRV_JOB_NAME=mrvAccountReferenceLoadJob"
                    + " materialises it into dcre_man.account";

    public AccountReferenceNotMaterialisedException(final String message) {
        super(message);
    }

    /** No {@code account_reference_load} row at all: the loader has never run against this database. */
    public static AccountReferenceNotMaterialisedException neverLoaded() {
        return new AccountReferenceNotMaterialisedException(
                "dcre_man.account was never materialised: account_reference_load holds no row, so no"
                        + " account reference load has ever run against this database. This is a"
                        + " deployment step that did not happen, NOT a business rejection: "
                        + MATERIALISATION_STEP + ".");
    }

    /**
     * A load ran and applied nothing. The loader refuses an artifact carrying no rows of MRV's
     * shape, so a zero-row ledger entry means the ledger and the master disagree with the
     * loader's own contract and the run must not proceed on an empty master.
     */
    public static AccountReferenceNotMaterialisedException loadedEmpty(final String datasetVersion) {
        return new AccountReferenceNotMaterialisedException(
                ("dcre_man.account is empty: the newest account_reference_load row records dataset %s"
                        + " with applied_row_count 0, so nothing was materialised. This is a"
                        + " deployment step that did not complete, NOT a business rejection: ")
                        .formatted(datasetVersion) + MATERIALISATION_STEP + ".");
    }
}
