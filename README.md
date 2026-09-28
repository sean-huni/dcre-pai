# dcre-pai

Account Init Service (ENDO): the account-init verdict applier and the fleet's single writer of the `account` table (R-04).

## Rename status (SCRUM-107): cut over

This repository is `dcre-ais` cloned and renamed to the name the payments REQ sheet has always
given this box, `PAI`, captioned "Account Init Service (ENDO)". The diagrams are the specification
(`design-register/CLAUDE.md`), and `ais` was this service under the wrong name.

The fleet side of the rename has since landed. Verified against the AGT sources on 2026-08-08:

| Fact | State |
|---|---|
| `RouteDags.ENDO` | `PRR -> PTV -> PAI -> fork {PRW, PIR}`, every stage a payments one |
| `Stage.PAI` / `AGT_PAI_IMAGE` | both exist; the pre-cutover `Stage.AIS` and `AGT_AIS_IMAGE` are gone |
| The pay-arm eligibility gate | `prw`'s `DueSql` counts rows in `pai_verdict`; `crw` is collections-only |

`pai_verdict` is this service's own table and `prw` is its consumer. The `ais_verdict` name survives
only in `prw`'s guard tests, which assert that the old name is NOT read (reading it would silently
count nothing), and in `crw`'s note that no changelog in the estate ever created it.

**The database is `dcre_pay`, the payments family's own.** It said `dcre_col` here until SCRUM-107,
because this repo is `dcre-ais` cloned and the collections datasource came across unchanged. That was
not a stale string: `AccountRepo` issues `INSERT INTO account`, so a clean clone built the entire PAI
schema and its `pai_databasechangelog` inside the COLLECTIONS database and wrote account rows there,
with no error at all. The write is perfectly valid against the wrong database, which is precisely why
nothing caught it.

## What it does

PAI is the post-validator account-init stage of the ENDO Payments request DAG. The payments REQ sheet draws it as `PRR -> PTV -> PAI -> || -> { PRW, PIR }`; the fleet today still runs the borrowed collections lane `CRR -> CTV -> AIS -> CIR` (SCRUM-69: CDE never runs on the pay flow, CRW picks pay-flow work up from ingest day via `tx_header.flow`), and the DC Collections route omits this stage entirely. For every spine transaction of an arrival it ensures the creditor account exists: known account -> verdict `EXISTS`; absent -> idempotent mint (R-11) with synthetic ENDO defaults (R-35) -> verdict `CREATED`, recorded immutably in `pai_verdict` keyed `(arrival_id, sequence)`. A missing account is never a rejection, it is a creation; an all-exist run is a valid no-op (A-7). PAI is DB-only for business file I/O (R-30), but on `COMPLETED` it writes the `BUSINESS_ACCEPTED` outcome seam file so the AGT reconciler can observe the stage (without it the run is reaped as `TECH_FAILED`).

## Architecture and principles

Spring Boot 4.1.0 / Spring Batch 6 / Java 25, run as a short-lived one-shot process (an ephemeral K8s Job minted by AGT), never a long-running server. `main` delegates to platform-batch's `ExitCodeMain`, so the JVM exit code carries the Batch outcome.

- **SOLID, 3-tier, layer-first packages**: the tasklet is a thin entry adapter (`AccountInitTasklet` extracts `arrival.id` and delegates), business logic lives in `service/AccountInitService`, persistence only via `data/repo` (`TxEntryRepo` read-only over CRR's spine, `AccountRepo`, `PaiVerdictRepo`). `CrdbRetry` is a single-purpose bounded-backoff unit for CockroachDB 40001 serialization aborts. One job `paiJob`, one tasklet step `accountInitStep`.
- **12FactorApp Alignment - https://12factor.net/**: config strictly from the environment with committed working dev defaults (a clean clone runs with no `.env`), stateless process, backing services (CockroachDB, exchange directory) as attached resources, dev/prod parity (the tests run the real job against a real CockroachDB container).
- **Idempotent restart semantics**: every write is create-if-absent on the full business identity. Accounts: `INSERT ... ON CONFLICT (account_number) DO NOTHING`. Verdicts: `INSERT ... ON CONFLICT (arrival_id, sequence) DO NOTHING`, never `DO UPDATE` (immutable: a rerun cannot flip `CREATED` to `EXISTS`) and never CRDB `UPSERT INTO` (UPSERT arbitrates on the PK only; the business identity is `(arrival_id, sequence)`). Verdicts commit in bounded sequence slices (`REQUIRES_NEW` per slice, keyset-paged reads inside the slice transaction), so a restart no-ops over committed slices and resumes the rest; one giant serializable transaction at 300k transactions is unrefreshable on CRDB (`RETRY_SERIALIZABLE`). 40001 aborts retry in a fresh transaction at both slice level (`CrdbRetry`, 5 attempts, exponential backoff with jitter) and step level (`CrdbRetryExceptionHandler`). The JobRepository dedupes relaunches on the identifying `arrival.id` parameter, and `StaleExecutionSweeper.abandonStale(ds, "PAI_BATCH_", 60)` runs before launch so a killed pod's stranded `STARTED` execution never blocks the relaunch.

Persistence notes: `AccountRepo` extends `Repository`, not `CrudRepository` (the derived `save()` path cannot satisfy the shared table's NOT NULL contract from the minimal `AccountEntity`; only targeted exists/create queries are exposed). Minted accounts carry SYNTHETIC-CONTRACT dev defaults per the toolkit fixture DDL (R-35): `product_code FNBRF`, `acc_type CACC`, `balance 999999999.99`, `process_status ACTIVE`, `status AAUT`, `branch_code 250205`, `ucn 100000000000`, `client_id 2`, `app_no` mirrors the account number.

Liquibase XML changelog master, per-service history tables `pai_databasechangelog` / `pai_databasechangeloglock` in `dcre_pay`:

1. `001-pai.xml`: `pai_verdict`, `UNIQUE (arrival_id, sequence)`, `action` is `EXISTS` or `CREATED`. Typed Liquibase tags, created unguarded.
2. `002-batch-metadata.xml`: Spring Batch metadata under prefix `PAI_BATCH_` (`initialize-schema: never`), loaded from `batch-metadata-pai.sql` via `<sqlFile>`.

**PAI no longer creates its read sources, and that is an ordering contract.** `000-bootstrap.xml` is
deleted. It bootstrap-minted three relations PAI does not own, in unguarded raw-SQL blocks, so that
PAI could run on a fresh database before their owners ever had: `tx_header` and `tx_entry` (PRR owns
them, R-04 single writer) and `account`. PRR's baseline creates its two unguarded, so a PAI mint that
won the race would crashloop PRR on "relation already exists"; the mint was also a hand-copied mirror
nothing compared against the owner, so PAI's suite could stay green against a column set PRR had
already changed. **PAI's migration must therefore run AFTER PRR's.** On a fresh `dcre_pay`, PAI
migrating first now fails loudly and names the missing relation instead of inventing a wrong one.
This is the retirement `collections/crg` performed first, copied rather than reinvented.

**Open edge, recorded rather than left to be discovered:** nothing creates `account` in `dcre_pay`,
because nothing should. A new `acs` service is being built to own it in its own `dcre_acs` database.
`AccountRepo` still issues `INSERT INTO account` against the primary datasource, so PAI cannot
complete a run against a real `dcre_pay` until `acs` ships and `AccountRepo` is repointed. That
repointing is owned elsewhere and is deliberately not worked around here with a mint, because a mint
is what put PAI's schema in the wrong database to begin with.

`pai_verdict` is the relation `prw` reads: `prw`'s `DueSql.DUE_GATES` counts rows in it against
`dcre_pay`, and `001-pai.xml` is the only thing in the estate that creates it. That is why the
`dcre_col` default was fatal rather than untidy.

Integration tests stand the read sources up from
`src/test/resources/db/changelog/test/001-read-sources.xml`, reached through
`db.changelog-test-master.xml`, which then runs the production master unchanged.

## Prerequisites

- Java 25 (Gradle toolchain; wrapper is Gradle 9.5.1)
- Docker (Testcontainers CockroachDB in tests; image builds)
- Platform libs in Maven Local: `za.co.fnb.dcre:platform-batch:0.1.0` and `za.co.fnb.dcre:platform-persistence:0.1.0` (no remote repository)

## Quickstart

Clean clone, no `.env`: committed defaults target `localhost:26257/dcre_pay`; env vars override.

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
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_pay?sslmode=disable` | The payments CockroachDB via pgwire. AGT injects this exact name into every stage Job and routes the value per family. |
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

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)
