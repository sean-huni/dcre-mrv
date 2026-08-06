# dcre-mrv

Mandates Request Validator: the second stage of the M10 mandates flow (SCRUM-75) that validates the mandate request spine written by MRR, records a durable per-record verdict in `man_validation_log`, and transitions each spine row's `spine_state` from `RECEIVED` to `VALIDATED` or `REJECTED` in `dcre_man`.

## What it does

MRV is the DAG successor of MRR (`MRR -> MRV -> MAF -> MIT -> { MIR || MRW }`). AGT launches it as a short-lived Kubernetes Job with `arrival.id` as the identifying JobParameter (R-16). For every instruction record on the spine it runs the item-tier precedence chain, writes the verdict, and advances the `spine_state` column it owns (ruling note 2: MRR writes spine ROWS, MRV/MAF/MIT each advance the state columns they own).

### The VerdictChain (pure static, CTV pattern)

`service/VerdictChain.classify` is a side-effect-free, DB-free precedence chain (mirrors CTV's `VerdictChain`), verdicts drawn from the fixed `platform-model` `MandateOutcome` vocabulary. Stages, in strict order:

1. **structure** (`FAIL_STRUCTURE`): `record_type` / `action_code` (`CREATE|AMEND|CANCEL`) / `mandate_ref` / `currency` well-formed. A structural fault is a whole-file fatal (rolls up `BUSINESS_FILE_REJECTED`).
2. **duplicate mandate_ref in-file** (`FAIL_DUPLICATE_REF`): the MRR-flagged `dup_in_file` later occurrence (B1a).
3. **account exists** (`FAIL_ACCOUNT_NOT_FOUND`): the debtor account is present in the `dcre_man` `account` master.
4. **account_type allows mandates** (`FAIL_ACCOUNT_TYPE_DISALLOWED`, AG01 class): `account_type.mandates_allowed`.
5. **contract format** (`FAIL_CONTRACT_FORMAT`, A-61 SYNTHETIC): `contract_ref` matches the un-attested format rule (non-blank alphanumeric up to width 14).
6. **action-specific**: AMEND/CANCEL require the `mandate_ref` to be a KNOWN prior registration on the spine or projection (`FAIL_AMEND_UNKNOWN_REF` / `FAIL_CANCEL_UNKNOWN_REF`); CREATE requires its ABSENCE, a collision with a prior registration reusing `FAIL_DUPLICATE_REF` (the only ref-clash code in the fixed vocabulary). "Known" excludes the current arrival's own rows, so a CREATE never collides with its own RECEIVED spine row.

### AS-OF snapshot (CTV F51 pattern)

`data/repo/ManReferenceSnapshotDao` reads the `account`, `account_type`, and known-ref stores via CockroachDB `AS OF SYSTEM TIME` at a single HLC captured once at the header step (`cluster_logical_timestamp()`), so every row of an arrival is judged against one consistent MVCC view even under a concurrent reference mutation. The as-of reads run on their own read-only connection. MRV runs on the default SERIALIZABLE isolation (the HLC snapshot is SERIALIZABLE-only; only PRG carries READ COMMITTED, SCRUM-90).

### Outcome rollup (R-41) and spine transition

`service/ManRollupService` derives the seam verdict from the durable `man_validation_log` and transitions the spine accordingly:

- a structural fatal (any `FAIL_STRUCTURE`), or any item fail under `ALL_OR_NOTHING` (the default acceptance mode), rejects the whole file (`BUSINESS_FILE_REJECTED`), so **every** RECEIVED row goes REJECTED and nothing proceeds to MAF;
- under `PARTIAL`, passing rows advance to VALIDATED while failing rows go REJECTED (`BUSINESS_PARTIAL`);
- a clean arrival is `BUSINESS_ACCEPTED`, all rows VALIDATED.

### Idempotency and resume (chaos-monkey gate)

- `man_validation_log` is written through `INSERT ... ON CONFLICT (arrival_id, sequence) DO NOTHING` (never UPSERT on the PK: CRDB resolves UPSERT on PK only), so a re-run is a zero-duplicate no-op on the FULL business identity.
- Every `spine_state` transition is a GUARDED atomic UPDATE `WHERE spine_state = 'RECEIVED'`, so it is idempotent + resumable (a re-run touches zero already-transitioned rows) and non-clobbering (a downstream stage's SCORE_*/INITIALIZED is never RECEIVED).
- Verified by `MrvJobIT.reRunIsIdempotentZeroDuplicateAndSpineStable`: a fresh JobInstance reprocessing the same arrival leaves `count(*) == count(DISTINCT (arrival_id, sequence))` and the spine unchanged.

## Architecture

Ephemeral Spring Boot 4.1.0 / Spring Batch 6 / Java 25 batch job cloned from the CRR/MRR skeleton: `ExitCodeMain` wires the Batch outcome into the JVM exit code (R-34), CockroachDB via the PostgreSQL driver, platform-batch persistent JobRepository (`@Import BatchJdbcConfig`, `MRV_BATCH_` prefix), layer-first packages (`config/`, `service/`, `data/model/`, `data/repo/`).

1. `headerSnapshotStep` (tasklet): resolves the acceptance-mode client token and captures the single F51 as-of HLC into the job execution context.
2. `validateStep` (tasklet): the item-tier VerdictChain pass over the spine, reading reference data AS OF the captured HLC, writing `man_validation_log`. Carries the shared `CrdbRetryExceptionHandler` (40001 re-runs the tasklet).
3. `rollupStep` (tasklet): the R-41 acceptance-mode exit + the `spine_state` transition, derived from the durable log.

`BatchMetaConfig` sweeps stale `MRV_BATCH_` executions to ABANDONED before the runner fires (A-39a).

## Database

Liquibase owns the schema in the shared `dcre_man`, per-service history tables (`mrv_databasechangelog` / `mrv_databasechangeloglock`), calendar layout `2026/07/`, pure-XML typed changesets (MARK_RAN bootstrap guards):

- `000-man-core-bootstrap.xml`: byte-equivalent VERBATIM copy of MRR's shared-core bootstrap (only the changeset ids are `mrv-` prefixed, per the shared-core canon) so concurrent first runs of any M-service converge.
- `001-man-validation-log.xml`: `man_validation_log` (`arrival_id`, `sequence`, `outcome`, `detail`, UNIQUE (`arrival_id`, `sequence`)). MRV is the sole writer (R-04).
- `002-batch-metadata.xml`: Liquibase-owned Spring Batch 6.0.4 DDL (`batch-metadata-mrv.sql`), prefixed `MRV_BATCH_`, EXIT_MESSAGE widened to TEXT for CockroachDB.

MRV reads the MRR-owned spine (`mandate_request_header` / `mandate_request_entry`) and the shared core (`account` / `account_type` / `mandate`); it never re-declares the spine in its own changelog (CTV reads the crr-owned tx spine the same way).

## Tests

- `service/VerdictChainTest`: pure-unit coverage of every chain stage (positive + negative) and the precedence guarantees.
- `MrvJobIT`: the full job over real CockroachDB (Testcontainers) for every stage end to end, the R-41 rollup modes (ACCEPTED / PARTIAL / FILE_REJECTED, structural override), the projection + prior-spine known-ref checks, and the resume/idempotency zero-duplicate audit.
- `config/AcceptanceModePropertiesTest`: acceptance-mode resolution.
