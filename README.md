# dcre-mrv

> Part of the DCRE fleet. For the fleet map, the rulings and the diagrams that specify every stage, start at the [DCRE design register](https://github.com/sean-huni/dcre-design-register); the complete list of live repositories is its [Repositories](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories) table.

Mandates Request Validator: the second stage of the M10 mandates flow (SCRUM-75) that validates the mandate request spine written by MRR, records a durable per-record verdict in `man_validation_log`, and transitions each spine row's `spine_state` from `RECEIVED` to `VALIDATED` or `REJECTED` in `dcre_man`.

## What it does

| | |
|---|---|
| Stage code | `MRV` (AGT `Stage.MRV`) |
| Family | Mandates (pain.009, `dcre_man`) |
| Leg | REQ |
| Trigger | Arrival-launched: AGT launches it when MRR completes on an `onhost-req-man` arrival |
| Upstream | `MRR` |
| Downstream | `MAS`. On `BUSINESS_FILE_REJECTED` AGT hands the rejection to `MIR` as `outcome.hint` |
| Diagram sheet | `dcre-mandates-req` in the design register |

DAG position read from AGT `origin/dev` `RouteDags.java` and `Stage.java` (checked 2026-09-28): `MRR -> MRV -> MAS -> MIT -> fork {MIR, MRW}`.

MRV also ships a second, non-DAG job, `mrvAccountReferenceLoadJob` (see below).

MRV is the DAG successor of MRR (`MRR -> MRV -> MAS -> MIT -> { MIR || MRW }`). AGT launches it as a short-lived Kubernetes Job with `arrival.id` as the identifying JobParameter (R-16). For every instruction record on the spine it runs the item-tier precedence chain, writes the verdict, and advances the `spine_state` column it owns (ruling note 2: MRR writes spine ROWS, MRV/MAS/MIT each advance the state columns they own).

### The VerdictChain (pure static, CTV pattern)

`service/VerdictChain.classify` is a side-effect-free, DB-free precedence chain (mirrors CTV's `VerdictChain`), verdicts drawn from the fixed `platform-model` `MandateOutcome` vocabulary. Stages, in strict order:

1. **structure** (`FAIL_STRUCTURE`): `record_type` / `action_code` (`CREATE|AMEND|CANCEL`) / `mandate_ref` / `currency` well-formed. A structural fault is a whole-file fatal (rolls up `BUSINESS_FILE_REJECTED`).
2. **duplicate mandate_ref in-file** (`FAIL_DUPLICATE_REF`): the MRR-flagged `dup_in_file` later occurrence (B1a).
3. **account exists** (`FAIL_ACCOUNT_NOT_FOUND`): the debtor account is present in the `dcre_man` `account` master.
4. **account_type allows mandates** (`FAIL_ACCOUNT_TYPE_DISALLOWED`, AG01 class): `account_type.mandates_allowed`.
5. **contract format** (`FAIL_CONTRACT_FORMAT`, A-61 SYNTHETIC): `contract_ref` matches the un-attested format rule (non-blank alphanumeric up to width 14).
6. **action-specific**: AMEND/CANCEL require the `mandate_ref` to be a KNOWN prior registration, either decided in the MRG-derived `mandate_effective_status` view or already on a prior arrival's spine (`FAIL_AMEND_UNKNOWN_REF` / `FAIL_CANCEL_UNKNOWN_REF`); CREATE requires its ABSENCE, a collision with a prior registration reusing `FAIL_DUPLICATE_REF` (the only ref-clash code in the fixed vocabulary). "Known" excludes the current arrival's own rows, so a CREATE never collides with its own RECEIVED spine row.
7. **contract admissibility** (`CONTRACT_HAS_LIVE_MANDATE`, SCRUM-91): at most one effectively live mandate per contract. `LiveMandateGuard` looks the contract up in the MRG-owned `mandate_current_status` view (live = `PDNG`, `ACCP`, `SUSPENDED`), excluding the current arrival; an AMEND of the same `mandate_ref` is the live mandate itself and passes. There is no database backstop (A-73), so MRV is the only enforcement point.

### AS-OF snapshot (CTV F51 pattern)

`data/repo/ManReferenceSnapshotDao` reads the `account`, `account_type`, and known-ref stores via CockroachDB `AS OF SYSTEM TIME` at a single HLC captured once at the header step (`cluster_logical_timestamp()`), so every row of an arrival is judged against one consistent MVCC view even under a concurrent reference mutation. The as-of reads run on their own read-only connection. MRV runs on the default SERIALIZABLE isolation (the HLC snapshot is SERIALIZABLE-only; the report generators CRG, PRG and MRG run READ COMMITTED via their `application.yml`, SCRUM-90; checked 2026-09-28).

### Outcome rollup (R-41) and spine transition

`service/ManRollupService` derives the seam verdict from the durable `man_validation_log` and transitions the spine accordingly:

- a structural fatal (any `FAIL_STRUCTURE`), or any item fail under `ALL_OR_NOTHING` (the default acceptance mode), rejects the whole file (`BUSINESS_FILE_REJECTED`), so **every** RECEIVED row goes REJECTED and nothing proceeds to MAS;
- under `PARTIAL`, passing rows advance to VALIDATED while failing rows go REJECTED (`BUSINESS_PARTIAL`);
- a clean arrival is `BUSINESS_ACCEPTED`, all rows VALIDATED.

### Idempotency and resume (chaos-monkey gate)

- `man_validation_log` is written through `INSERT ... ON CONFLICT (arrival_id, sequence) DO NOTHING` (never UPSERT on the PK: CRDB resolves UPSERT on PK only), so a re-run is a zero-duplicate no-op on the FULL business identity.
- Every `spine_state` transition is a GUARDED atomic UPDATE `WHERE spine_state = 'RECEIVED'`, so it is idempotent + resumable (a re-run touches zero already-transitioned rows) and non-clobbering (a downstream stage's SCORE_*/INITIALIZED is never RECEIVED).
- Verified by `MrvJobIT.reRunIsIdempotentZeroDuplicateAndSpineStable`: a fresh JobInstance reprocessing the same arrival leaves `count(*) == count(DISTINCT (arrival_id, sequence))` and the spine unchanged.

## Architecture and principles

Ephemeral Spring Boot 4.1.0 / Spring Batch 6 / Java 25 batch job cloned from the CRR/MRR skeleton: `ExitCodeMain` wires the Batch outcome into the JVM exit code (R-34), CockroachDB via the PostgreSQL driver, platform-batch persistent JobRepository (`@Import BatchJdbcConfig`, `MRV_BATCH_` prefix), layer-first packages (`config/`, `service/`, `data/model/`, `data/repo/`).

1. `headerSnapshotStep` (tasklet): resolves the acceptance-mode client token and captures the single F51 as-of HLC into the job execution context.
2. `validateStep` (tasklet): the item-tier VerdictChain pass over the spine, reading reference data AS OF the captured HLC, writing `man_validation_log`. Carries the shared `CrdbRetryExceptionHandler` (40001 re-runs the tasklet).
3. `rollupStep` (tasklet): the R-41 acceptance-mode exit + the `spine_state` transition, derived from the durable log.

`BatchMetaConfig` sweeps stale `MRV_BATCH_` executions to ABANDONED before the runner fires (A-39a). The seam verdict (`OutcomeSeamListener("mrv", ...)`) is `BUSINESS_ACCEPTED`, `BUSINESS_PARTIAL` or `BUSINESS_FILE_REJECTED`, written to `<DCRE_EXCHANGE_ROOT>/outcomes/<JOB_NAME>`.

### Account reference load (second job)

`spring.batch.job.name` is bound to `DCRE_MRV_JOB_NAME` (default `mrvJob`); setting it to `mrvAccountReferenceLoadJob` runs the one-step load instead of validation. The load reads a versioned artifact from `<DCRE_MRV_ACCOUNT_REFERENCE_ROOT>/<DCRE_MRV_ACCOUNT_DATASET_VERSION>` (root defaults to `<DCRE_EXCHANGE_ROOT>/reference/account`), checks the manifest's dataset version and schema version, the checksum, and a freshness gate that is wired but INERT while `max-age` is unset (A-4), then in ONE transaction deletes every `account` row, inserts the projection and records an `account_reference_load` row. Any failure rolls the whole load back. It never touches `account_type`.

Validation refuses to judge against an empty master: `AccountReferenceGuard` reads the newest `account_reference_load` row AS OF the run's HLC and throws when none exists or it applied zero rows, so a missed load fails the run instead of reporting `FAIL_ACCOUNT_NOT_FOUND` for every row. AGT on `origin/dev` has no reference to this job or to `DCRE_MRV_JOB_NAME` (checked 2026-09-28), so it is launched outside the DAG.

### Database

One business datasource: `dcre_man` via `DCRE_DB_URL` / `DCRE_DB_USER` / `DCRE_DB_PASSWORD`. A second datasource (`DCRE_AGTOPS_DB_*`, platform-batch `HeartbeatDatasourceConfig`) carries only the `HeartbeatWriter` liveness stamp into `agt_ops`. `OneMandateDatabaseTest` guards that the account master is read from `dcre_man` and nowhere else.

- Writes: `man_validation_log`; `mandate_request_entry.spine_state` (`RECEIVED -> VALIDATED | REJECTED` only); `account` and `account_reference_load` (reference load job only); its own `MRV_BATCH_*` metadata.
- Reads (AS OF one HLC): `account`, `account_type`, `account_reference_load`, `mandate_request_header`, `mandate_request_entry`, and the MRG-owned views `mandate_effective_status` and `mandate_current_status`.

Liquibase owns the schema in the shared `dcre_man`, per-service history tables (`mrv_databasechangelog` / `mrv_databasechangeloglock`), calendar layout `2026/07/`, pure-XML typed changesets. This changelog is the **v1 baseline** (SCRUM-107): every DCRE database is dropped and recreated for the direct cut-over, so there is no historic state to converge and no retrofit apparatus. The only `MARK_RAN` preconditions that remain are convergence guards on objects with more than one creator, and each says at the changeset which writer it converges with.

- `000-man-core-bootstrap.xml`: the shared reference core (`account_type`, `account`, `mandate_reason_code` plus their seeds), structurally identical (comments and the `mrv-` changeset id prefix aside) to the copies in the other nine mandates stages, mrr, mas, mit, mir, mrw, mix, msx, mpx and mrg (ten copies in all, one per mandates stage; checked 2026-09-28). Guarded, because the dcre-infra `seed-man-core.sql` bootstrap and any sibling mandates service can create these first.
- `001-man-validation-log.xml`: `man_validation_log` (`arrival_id`, `sequence`, `outcome`, `detail`, UNIQUE (`arrival_id`, `sequence`)). MRV is the sole WRITER of its rows (R-04); the create is guarded because MRG pre-creates the same table in its `004-man-views.xml`.
- `002-batch-metadata.xml`: Liquibase-owned Spring Batch 6.0.4 metadata as typed XML, one changeset per object, prefixed `MRV_BATCH_`, EXIT_MESSAGE widened to TEXT for CockroachDB. Unguarded: MRV is the only creator of its own prefix.
- `2026/08/003-account-reference-load.xml`: `account_reference_load`, the provenance ledger of each reference load (`dataset_version`, `schema_version`, `source_id`, `effective_ts`, `publication_ts`, `row_count`, `checksum`, `applied_row_count`, `job_execution_id`).

MRV reads the MRR-owned spine (`mandate_request_header` / `mandate_request_entry`), the shared core (`account` / `account_type`) and the MRG-owned derived status views; it never re-declares the spine in its own changelog (CTV reads the crr-owned tx spine the same way).

## Prerequisites

- Java 25: `.sdkmanrc` pins `java=25-tem` (`sdk env`); `build.gradle` sets source/target compatibility 25.
- Gradle 9.5.1 through the committed wrapper (`gradle/wrapper/gradle-wrapper.properties`).
- Docker: Testcontainers CockroachDB for the tests, and the image build.
- Platform libs in Maven Local: `za.co.fnb.dcre:platform-persistence:0.1.0` and `platform-batch:0.1.0` (`MandateOutcome` from `platform.model` arrives transitively).
- For a real local run: CockroachDB on `localhost:26257` with `dcre_man` and `agt_ops`, the MRG views present in `dcre_man`, and a materialised account reference load.

## Quickstart

Clean clone, no `.env` needed (working dev defaults committed in `application.yml`):

```bash
./gradlew test          # full suite, Docker required
./gradlew bootJar       # build/libs/mrv-2.0.jar

# validation (default job)
java -jar build/libs/mrv-2.0.jar 'arrival.id=<uuid>,java.lang.String,true'

# account reference load
DCRE_MRV_JOB_NAME=mrvAccountReferenceLoadJob java -jar build/libs/mrv-2.0.jar
```

## Configuration

All keys live in `src/main/resources/application.yml` (the only profile). Spring relaxed binding lets any property be overridden by its environment-variable form, so this table is the documented set, not a closed total.

| Env | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_man?sslmode=disable` | Mandates DB (CockroachDB) |
| `DCRE_DB_USER` | `root` | DB user |
| `DCRE_DB_PASSWORD` | (empty) | DB password |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | Heartbeat datasource |
| `DCRE_AGTOPS_DB_USER` | `root` | Heartbeat DB user |
| `DCRE_AGTOPS_DB_PASSWORD` | (empty) | Heartbeat DB password |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | Exchange root for the outcome seam, and base of the reference root |
| `DCRE_MRV_JOB_NAME` | `mrvJob` | Selects the job: `mrvJob` or `mrvAccountReferenceLoadJob` |
| `DCRE_MRV_ACCOUNT_REFERENCE_ROOT` | `${DCRE_EXCHANGE_ROOT}/reference/account` | Directory holding versioned account artifacts |
| `DCRE_MRV_ACCOUNT_DATASET_VERSION` | `2026.08.09-001` | The one dataset version this build demands (never "newest") |
| `JOB_NAME` | unset (`local-mrv-<executionId>`) | Set by AGT; names the outcome seam file |

Keys without an env placeholder in yml:

- `dcre.mrv.reference.account.max-age` (unset): a `Duration` that arms the freshness gate (A-4).
- `dcre.mrv.acceptance-mode.default` (`ALL_OR_NOTHING`) and `dcre.mrv.acceptance-mode.clients."[TOKEN]"` (`PARTIAL`): R-41 mode per client; binding is to the enum, so an unknown value fails startup. Client keys are matched exactly against the uppercase token, so write overrides in yml with bracketed keys as the committed comment shows.
- `dcre.batch.table-prefix: MRV_BATCH_`, Liquibase history tables `mrv_databasechangelog` / `mrv_databasechangeloglock`.

## Testing

`./gradlew test` (Docker required; `useJUnitPlatform()` with no filter, so `*IT` classes run in the same task). Testcontainers image: `cockroachdb/cockroach:v26.2.3`.

- `service/VerdictChainTest`: pure-unit coverage of every chain stage (positive + negative) and the precedence guarantees.
- `MrvJobIT`: the full job over real CockroachDB for every stage end to end, the R-41 rollup modes (ACCEPTED / PARTIAL / FILE_REJECTED, structural override), the derived-status + prior-spine known-ref checks, and the resume/idempotency zero-duplicate audit.
- `service/OneLiveMandatePerContractIT`: the `CONTRACT_HAS_LIVE_MANDATE` invariant through the real job.
- `service/AccountReferenceLoadIT`, `AccountReferenceMaterialisationIT`, `AccountReferenceRefusalIT`: the reference load, its all-or-nothing materialisation, and each refusal path.
- `OneMandateDatabaseTest`: the account master is read from `dcre_man` only.
- `MrgChangelogDriftTest`: the MRG changesets copied into `src/test/resources/mrg-changelog/` are byte-identical to MRG's own; SKIPPED (not failed) when MRG is not checked out beside MRV at `../mrg`.
- `config/AcceptanceModePropertiesTest`: acceptance-mode resolution.

No Cucumber features ship in this repo, although the Cucumber dependencies are declared.

## Local cluster deployment

```bash
./gradlew bootJar
docker build -t dcre-mrv:<version> .
kind load docker-image --name dcre-dev dcre-mrv:<version>
```

Image base: `eclipse-temurin:25-jre-alpine` (`Dockerfile` copies `build/libs/mrv-2.0.jar`). AGT launches MRV as a K8s Job in the mandates flow namespace (`AGT_NAMESPACE_MAN`, default `dcre-man`) with the image from `AGT_MRV_IMAGE` (empty default = launch-disabled) and the single arg `arrival.id=<uuid>`. AGT injects `JOB_NAME`, `DCRE_DB_URL` (from `AGT_MAN_SERVICE_DB_URL`, default `dcre_man` on `crdb.dcre.svc.cluster.local`), `DCRE_EXCHANGE_ROOT=/exchange` (the `dcre-exchange` PVC, so the reference root resolves to `/exchange/reference/account`), `DCRE_AGTOPS_DB_URL` and `DCRE_AGTOPS_DB_USER` (AGT `origin/dev` `JobLauncher.java` and `application.yml`, checked 2026-09-28). The cluster itself, and the fleet-wide image switch, live in dcre-infra.

## Related repositories

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)
