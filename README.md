# dcre-pai

Account Init Service (ENDO): the account-init verdict applier and the fleet's single writer of the `account` table (R-04).

## Rename status (SCRUM-107): what is and is not wired yet

This repository is `dcre-ais` cloned and renamed to the name the payments REQ sheet has always
given this box, `PAI`, captioned "Account Init Service (ENDO)". The diagrams are the specification
(`design-register/CLAUDE.md`), and `ais` was this service under the wrong name.

The rename is complete INSIDE this repository and deliberately stops at its boundary. Three things
are still true of the running fleet and are NOT this repo's to change:

| Fact | State | Owner of the change |
|---|---|---|
| `RouteDags.ENDO` is `CRR -> CTV -> AIS -> CIR` | still names `Stage.AIS` | AGT, later sequencing step |
| `AGT_PAI_IMAGE` / `Stage.PAI` | do not exist yet; AGT knows only `AGT_AIS_IMAGE` | AGT, applied in one pass |
| `crw` reads `ais_verdict` on the pay arm | unchanged, so `dcre-ais` remains the live producer | CRW owner |

**This service is therefore not yet launchable as `PAI`, and `dcre-ais` is still the deployed
account-init stage.** `pai_verdict` here is a fresh table, not a rename of the live `ais_verdict`:
`crw`'s pay-flow eligibility gate counts rows in `ais_verdict`
(`DueSql.PAY_DUE_GATES`), so nothing may drop or rename that table until CRW is repointed.
The database also stays `dcre_col`; the payments family's own `dcre_pay` does not exist yet.

## What it does

PAI is the post-validator account-init stage of the ENDO Payments request DAG. The payments REQ sheet draws it as `PRR -> PTV -> PAI -> || -> { PRW, PIR }`; the fleet today still runs the borrowed collections lane `CRR -> CTV -> AIS -> CIR` (SCRUM-69: CDE never runs on the pay flow, CRW picks pay-flow work up from ingest day via `tx_header.flow`), and the DC Collections route omits this stage entirely. For every spine transaction of an arrival it ensures the creditor account exists: known account -> verdict `EXISTS`; absent -> idempotent mint (R-11) with synthetic ENDO defaults (R-35) -> verdict `CREATED`, recorded immutably in `pai_verdict` keyed `(arrival_id, sequence)`. A missing account is never a rejection, it is a creation; an all-exist run is a valid no-op (A-7). PAI is DB-only for business file I/O (R-30), but on `COMPLETED` it writes the `BUSINESS_ACCEPTED` outcome seam file so the AGT reconciler can observe the stage (without it the run is reaped as `TECH_FAILED`).

## Architecture and principles

Spring Boot 4.1.0 / Spring Batch 6 / Java 25, run as a short-lived one-shot process (an ephemeral K8s Job minted by AGT), never a long-running server. `main` delegates to platform-batch's `ExitCodeMain`, so the JVM exit code carries the Batch outcome.

- **SOLID, 3-tier, layer-first packages**: the tasklet is a thin entry adapter (`AccountInitTasklet` extracts `arrival.id` and delegates), business logic lives in `service/AccountInitService`, persistence only via `data/repo` (`TxEntryRepo` read-only over CRR's spine, `AccountRepo`, `PaiVerdictRepo`). `CrdbRetry` is a single-purpose bounded-backoff unit for CockroachDB 40001 serialization aborts. One job `paiJob`, one tasklet step `accountInitStep`.
- **12FactorApp Alignment - https://12factor.net/**: config strictly from the environment with committed working dev defaults (a clean clone runs with no `.env`), stateless process, backing services (CockroachDB, exchange directory) as attached resources, dev/prod parity (the tests run the real job against a real CockroachDB container).
- **Idempotent restart semantics**: every write is create-if-absent on the full business identity. Accounts: `INSERT ... ON CONFLICT (account_number) DO NOTHING`. Verdicts: `INSERT ... ON CONFLICT (arrival_id, sequence) DO NOTHING`, never `DO UPDATE` (immutable: a rerun cannot flip `CREATED` to `EXISTS`) and never CRDB `UPSERT INTO` (UPSERT arbitrates on the PK only; the business identity is `(arrival_id, sequence)`). Verdicts commit in bounded sequence slices (`REQUIRES_NEW` per slice, keyset-paged reads inside the slice transaction), so a restart no-ops over committed slices and resumes the rest; one giant serializable transaction at 300k transactions is unrefreshable on CRDB (`RETRY_SERIALIZABLE`). 40001 aborts retry in a fresh transaction at both slice level (`CrdbRetry`, 5 attempts, exponential backoff with jitter) and step level (`CrdbRetryExceptionHandler`). The JobRepository dedupes relaunches on the identifying `arrival.id` parameter, and `StaleExecutionSweeper.abandonStale(ds, "PAI_BATCH_", 60)` runs before launch so a killed pod's stranded `STARTED` execution never blocks the relaunch.

Persistence notes: `AccountRepo` extends `Repository`, not `CrudRepository` (the derived `save()` path cannot satisfy the shared table's NOT NULL contract from the minimal `AccountEntity`; only targeted exists/create queries are exposed). Minted accounts carry SYNTHETIC-CONTRACT dev defaults per the toolkit fixture DDL (R-35): `product_code FNBRF`, `acc_type CACC`, `balance 999999999.99`, `process_status ACTIVE`, `status AAUT`, `branch_code 250205`, `ucn 100000000000`, `client_id 2`, `app_no` mirrors the account number.

Liquibase XML changelog master, per-service history tables `pai_databasechangelog` / `pai_databasechangeloglock` on the shared `dcre_col` DB:

1. `000-bootstrap.xml`: bootstrap-order guards. PAI does not own `account` (the fixture toolkit seeds it in dev) but may run on a fresh DB first, so `account`, `tx_header` and `tx_entry` are created via `CREATE TABLE IF NOT EXISTS` (raw SQL changesets, not typed Liquibase tags) with the owners' exact DDL.
2. `001-pai.xml`: `pai_verdict`, `UNIQUE (arrival_id, sequence)`, `action` is `EXISTS` or `CREATED` (also a raw-SQL changeset).
3. `002-batch-metadata.xml`: Spring Batch metadata under prefix `PAI_BATCH_` (`initialize-schema: never`), loaded from `batch-metadata-pai.sql` via `<sqlFile>`.

## Prerequisites

- Java 25 (Gradle toolchain; wrapper is Gradle 9.5.1)
- Docker (Testcontainers CockroachDB in tests; image builds)
- Platform libs in Maven Local: `za.co.fnb.dcre:platform-batch:0.1.0` and `za.co.fnb.dcre:platform-persistence:0.1.0` (no remote repository)

## Quickstart

Clean clone, no `.env`: committed defaults target `localhost:26257/dcre_col`; env vars override.

```bash
# 1. Publish the platform libs to Maven Local (once). Run ./gradlew publishToMavenLocal
#    in dcre-platform-model, then dcre-platform-files, then dcre-platform-batch
#    (batch brings files and model transitively); dcre-platform-persistence is standalone.

# 2. Build and test (Docker required)
./gradlew build

# 3. One-shot run against a reachable CockroachDB
java -jar build/libs/pai-2.0.jar arrival.id=<uuid>
```

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_col?sslmode=disable` | CockroachDB via pgwire |
| `DCRE_DB_USER` | `root` | DB user |
| `DCRE_DB_PASSWORD` | (empty) | DB password |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | Outcome seam write only (no business file I/O, R-30) |
| `DCRE_PAI_VERDICT_SLICE_SIZE` | `10000` | Verdict slice commit size (bounded transactions at 300k-tx arrivals) |
| `JOB_NAME` | `local-<executionId>` | K8s-injected job identity; names the outcome seam file |

## Testing

```bash
./gradlew test
```

Real behavior, no mocks: Testcontainers `cockroachdb/cockroach:v26.2.3`.

- `PaiJobTest`: end-to-end job run (3 known + 2 unknown creditors -> 3 `EXISTS` + 2 `CREATED`, exactly 2 accounts minted), then a rerun asserting verdict immutability and zero duplicate mints (R-05).
- Cucumber BDD suite (`features/account-init.feature`, 6 scenarios): EXISTS/CREATED verdicts, rerun immutability, all-exist no-op (A-7), synthetic ENDO defaults on a minted account, mixed arrivals.
- `AccountInitServiceSliceTest`: slice-by-slice commit ratchet and 40001 retry semantics.
- `PaiJobConfigRetryTest`: proof the real `accountInitStep` retries a commit-time `TransientDataAccessException` in a fresh transaction.

## Local cluster deployment

```bash
./gradlew bootJar
docker build -t dcre-pai:2.1.1 .
kind load docker-image --name dcre-dev dcre-pai:2.1.1
```

The image (`eclipse-temurin:25-jre-alpine`) wraps `build/libs/pai-2.0.jar`; the release version is carried by the image tag (digits-only SemVer fleet tags, current `2.1.1`). Once AGT gains `Stage.PAI` and `AGT_PAI_IMAGE` (see the rename-status table above; neither exists yet, so building this image does not by itself put it in the DAG), AGT will launch `dcre-pai` as an ephemeral K8s Job, passing the identifying `arrival.id` program argument and injecting `JOB_NAME`, with the image switched fleet-wide by `dcre-infra scripts/switch-version.sh`. The kind cluster and CockroachDB come from `dcre-infra scripts/kind-up.sh`; `scripts/env-reset.sh` gives a clean slate.

## Related repositories

- Supersedes: [dcre-ais](https://github.com/sean-huni/dcre-ais) (deprecated, kept for history; still the live account-init stage until AGT is repointed)
- Orchestrator: [dcre-agt](https://github.com/sean-huni/dcre-agt)
- Stage services: [dcre-crr](https://github.com/sean-huni/dcre-crr), [dcre-ctv](https://github.com/sean-huni/dcre-ctv), [dcre-cde](https://github.com/sean-huni/dcre-cde), [dcre-cir](https://github.com/sean-huni/dcre-cir), [dcre-crw](https://github.com/sean-huni/dcre-crw), [dcre-ixr](https://github.com/sean-huni/dcre-ixr), [dcre-sxr](https://github.com/sean-huni/dcre-sxr), [dcre-pxr](https://github.com/sean-huni/dcre-pxr), [dcre-prg](https://github.com/sean-huni/dcre-prg), [dcre-hcs](https://github.com/sean-huni/dcre-hcs)
- Platform libs: [dcre-platform-model](https://github.com/sean-huni/dcre-platform-model), [dcre-platform-files](https://github.com/sean-huni/dcre-platform-files), [dcre-platform-batch](https://github.com/sean-huni/dcre-platform-batch), [dcre-platform-persistence](https://github.com/sean-huni/dcre-platform-persistence)
- Infra and tooling: [dcre-infra](https://github.com/sean-huni/dcre-infra), [dcre-fixture-toolkit](https://github.com/sean-huni/dcre-fixture-toolkit), [dcre-design-register](https://github.com/sean-huni/dcre-design-register), [dcre-rpt](https://github.com/sean-huni/dcre-rpt)
