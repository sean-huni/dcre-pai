# dcre-pai

> Part of the DCRE fleet. For the fleet map, the rulings and the diagrams that specify every stage, start at the [DCRE design register](https://github.com/sean-huni/dcre-design-register); the complete list of live repositories is its [Repositories](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories) table.

Account Init Service (ENDO): the payments stage that checks every transaction's creditor account
against the account master, records one immutable verdict per transaction in `pai_verdict`, and
records creditors it could not find in its own `unknown_creditor` table.

## What it does

| | |
| --- | --- |
| Stage code | `PAI` (AGT `Stage.PAI`), captioned "Account Init Service (ENDO)" |
| Family / leg | payments (ENDO), REQ |
| Trigger | arrival-launched: a DAG successor, one Kubernetes Job per arrival |
| Upstream | `PTV` |
| Downstream | the terminal fork `{PRW, PIR}` |
| Diagram sheet | `dcre-payments-req` in the design register |

AGT's `RouteDags.ENDO` (checked 2026-09-28) is `PRR -> PTV -> PAI -> fork {PRW, PIR}`, with no CDE
analogue and `Emission.NONE`: payments transactions are processed immediately. The DC collections
route has no PAI.

For every spine transaction of the arrival (`tx_entry`, read in keyset slices by `sequence`):

- creditor account present in `account` -> verdict `EXISTS`;
- absent -> the sighting is recorded in `unknown_creditor` (insert-once on `account_number`, first
  sighting wins) -> verdict `CREATED`.

Verdicts are immutable and an all-exist run is a valid no-op (A-7). **PAI does not write `account`.**
Until SCRUM-107 the absent branch minted an `account` row with hardcoded toolkit-sample values
(including a `999999999.99` balance "so caps pass"); with PTV's account tier now failing closed that
turned one rejection into a permanent pass, so the write moved to `unknown_creditor`. The verdict
value `CREATED` was kept because PRW and PRG read it as a cross-service contract.

PAI does no business file I/O (R-30). On COMPLETED it writes the `BUSINESS_ACCEPTED` outcome seam
file so the AGT reconciler can observe the stage.

### Lineage

This repository is `dcre-ais` cloned and renamed to the name the payments REQ sheet gives this box.
AGT carries `Stage.PAI` and `AGT_PAI_IMAGE`; the pre-cutover `Stage.AIS` / `AGT_AIS_IMAGE` are gone
(checked 2026-09-28). Its datasource default said `dcre_col` until SCRUM-107, so a clean clone
built the PAI schema inside the collections database without any error.

## Architecture and principles

Spring Boot 4.1.0 / Spring Batch 6 / Java 25, run as a one-shot process (an ephemeral K8s Job minted
by AGT). `main` delegates to platform-batch's `ExitCodeMain`, so the JVM exit code carries the Batch
outcome (R-34).

- **SOLID, 3-tier, layer-first packages**: `AccountInitTasklet` extracts `arrival.id` and delegates;
  business logic in `service/AccountInitService`; persistence only via `data/repo` (`TxEntryRepo`
  and `AccountRepo` read-only, `PaiVerdictRepo` and `UnknownCreditorRepo` the only writers).
  One job `paiJob`, one tasklet step `accountInitStep`.
- **12FactorApp Alignment - https://12factor.net/**: config strictly from the environment with
  committed working dev defaults (a clean clone runs with no `.env`), stateless process, CockroachDB
  and the exchange directory as attached resources.

### Database

- **Database today:** `dcre_pay`, via `DCRE_DB_URL` / `DCRE_DB_USER` / `DCRE_DB_PASSWORD`. A second
  datasource, `DCRE_AGTOPS_DB_URL` / `_USER` / `_PASSWORD`, targets `agt_ops` for the
  `HeartbeatWriter` liveness stamp on `agt_ops.launch_intent`.
- **Writes:** `pai_verdict`, `unknown_creditor`, `PAI_BATCH_*` metadata, Liquibase history
  `pai_databasechangelog` / `pai_databasechangeloglock`.
- **Reads:** `tx_entry` (created by PRR) and `account` (created in `dcre_pay` by PTV's
  `003-account-reference.xml` and filled by PTV's reference loader, checked 2026-09-28).

Liquibase master, pure changesets in calendar folders:

1. `2026/07/001-pai.xml`: `pai_verdict`, `UNIQUE (arrival_id, sequence)`, `action` `EXISTS` or
   `CREATED`. Typed tags, unguarded. PRW's eligibility gate counts rows in it.
2. `2026/07/002-batch-metadata.xml`: Spring Batch metadata under prefix `PAI_BATCH_`, loaded from
   `batch-metadata-pai.sql` via `<sqlFile>` with `IF NOT EXISTS` DDL and `validCheckSum ANY`.
3. `2026/08/003-unknown-creditor.xml`: `unknown_creditor` (`account_number` VARCHAR(34) unique,
   `arrival_id`, `created_at`). Typed tags, unguarded.

**PAI creates none of its read sources.** The former `000-bootstrap.xml` minted `tx_header`,
`tx_entry` and `account` so PAI could run on an empty database; it is deleted because a reader
minting its writer's schema makes a race crashloop the owner and hides column drift. PAI's own
migration touches only its three changesets; a RUN on a database where PRR and PTV have not yet
migrated fails on the first query, naming the missing relation, and is relaunched.

### Invariants

- **Full-identity idempotency**: verdicts `INSERT ... ON CONFLICT (arrival_id, sequence) DO NOTHING`
  (never `DO UPDATE`, so a rerun cannot flip `CREATED` to `EXISTS`; never CRDB `UPSERT INTO`, which
  arbitrates on the PK only). Sightings `ON CONFLICT (account_number) DO NOTHING`.
- **Bounded slices (SCRUM-42)**: verdicts commit per sequence slice (`REQUIRES_NEW`, keyset read
  inside the slice transaction, `dcre.pai.verdict-slice-size`, default 10000). 40001 aborts retry in
  a fresh transaction at slice level (`CrdbRetry`, 5 attempts, exponential backoff with jitter) and
  at step level (`CrdbRetryExceptionHandler("PAI")`).
- **Restart**: the JobRepository dedupes relaunches on the identifying `arrival.id`, and
  `StaleExecutionSweeper.abandonStale(ds, "PAI_BATCH_", 60)` runs at `@Order(-10)` before launch so a
  killed pod's stranded `STARTED` execution never blocks the relaunch (A-39a).
- **Fail closed on a missing master**: `AccountRepo` is a read-only probe; a missing `account`
  relation fails the run loudly rather than being worked around with a mint.

## Prerequisites

- Java 25: `.sdkmanrc` pins `java=25-tem`
- Gradle 9.5.1 via the committed wrapper
- Docker, for the Testcontainers suite and image builds
- Platform libs in Maven Local (no remote repository): `za.co.fnb.dcre:platform-batch:0.1.0` and
  `za.co.fnb.dcre:platform-persistence:0.1.0`

## Quickstart

Clean clone, no `.env`: committed defaults target `localhost:26257/dcre_pay`; env vars override.

```bash
# 1. Publish the platform libs to Maven Local (once). Run ./gradlew publishToMavenLocal
#    in dcre-platform-model, then dcre-platform-files, then dcre-platform-batch
#    (batch brings files and model transitively); dcre-platform-persistence is standalone.

# 2. Build and test (Docker required)
./gradlew build

# 3. One-shot run against a reachable CockroachDB where PRR and PTV have migrated
java -jar build/libs/pai-2.0.jar arrival.id=<uuid>
```

## Configuration

Precedence: `application.yml` default < environment variable.

| Env var | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_pay?sslmode=disable` | The payments CockroachDB via pgwire. AGT injects this exact name into every stage Job and routes the value per family. |
| `DCRE_DB_USER` | `root` | DB user |
| `DCRE_DB_PASSWORD` | (empty) | DB password |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | Heartbeat liveness stamp |
| `DCRE_AGTOPS_DB_USER` / `DCRE_AGTOPS_DB_PASSWORD` | `root` / (empty) | Heartbeat credentials |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | Outcome seam write only (no business file I/O, R-30) |
| `DCRE_PAI_VERDICT_SLICE_SIZE` | `10000` | Verdict slice commit size |
| `JOB_NAME` | unset: seam falls back to `local-pai-<executionId>` | K8s-injected job identity; names the outcome seam file |

This table is the documented set, not a closed total: Spring Boot relaxed binding lets any property
be overridden by its environment-variable form.

## Testing

```bash
./gradlew test
```

Real behaviour, no mocks: Testcontainers `cockroachdb/cockroach:v26.2.3`. The read sources
(`tx_header`, `tx_entry`, `account`) come from `src/test/resources/db/changelog/test/001-read-sources.xml`
via `db.changelog-test-master.xml`, which then runs the production master unchanged.

- `PaiJobTest`: 3 known + 2 unknown creditors -> 3 `EXISTS` + 2 `CREATED`, `account` row count
  unchanged, exactly 2 `unknown_creditor` rows; a rerun keeps verdicts immutable and records no
  duplicate sighting (R-05). A second test pins the `local-pai-<executionId>` seam fallback.
- `WritesOnlyWhatItOwnsTest`: no shipped statement writes a relation PAI does not own, the account
  master is read and only read, and the shipped changelog creates exactly the owned relations.
- `AgtWireContractTest`: PAI reads the `DCRE_DB_URL` name AGT injects (one-sided: it cannot see AGT).
- `ConfigPrefixParityTest`: every defaulted `${dcre.pai.*}` placeholder is backed by a key in the
  shipped yml, so a prefix rename cannot silently bind the constant default.
- `AccountInitServiceSliceTest`: slice-by-slice commit ratchet and 40001 retry semantics.
- `PaiJobConfigRetryTest`: the real `accountInitStep` retries a commit-time
  `TransientDataAccessException` in a fresh transaction.
- Cucumber (`CucumberSuiteTest`, `features/account-init.feature`): EXISTS and CREATED verdicts,
  rerun immutability without duplicate sightings, all-exist no-op (A-7), sighting provenance, mixed
  arrivals.

## Local cluster deployment

This repo still carries a hand-written `Dockerfile` (`eclipse-temurin:25-jre-alpine` wrapping
`build/libs/pai-2.0.jar`) and has no `bootBuildImage` configuration:

```bash
./gradlew bootJar
docker build -t dcre-pai:2.0 .
kind load docker-image --name dcre-dev dcre-pai:2.0
kubectl set env -n dcre deploy/dcre-agt AGT_PAI_IMAGE=dcre-pai:2.0
```

The cluster comes from `dcre-infra` (`scripts/kind-up.sh`; `scripts/env-reset.sh` for a clean
slate). AGT resolves the image from `AGT_PAI_IMAGE` (empty means launch-disabled);
`scripts/switch-version.sh` does not export it (its roster still lists `AIS`, checked 2026-09-28),
hence the explicit `kubectl set env`. AGT launches the Job in the `dcre-pay` namespace with the
single identifying program arg `arrival.id=<uuid>` and env `JOB_NAME`, `DCRE_DB_URL` (the `dcre_pay`
URL), `DCRE_EXCHANGE_ROOT=/exchange`, `DCRE_AGTOPS_DB_URL` and `DCRE_AGTOPS_DB_USER`.

## Related repositories

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)
