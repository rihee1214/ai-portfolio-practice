# Isolated local release operations

These commands implement the accepted P12-D local rehearsal. They never build or pull images, remove a database/volume/media directory, repair Flyway, contact a deployment provider, or publish beyond loopback. Every request explicitly identifies its target. Cached PowerShell 7, Node, Docker/Compose, artifacts, TLS and invitation credentials must already exist.

Before preparing a target, retain the execution closure with `node infra/p12-d/payload.cjs <payload-definition.json> <new-payload-record.json>`. The definition is `{sourceRoot,outputRoot,kind,sourceReleaseId,sourceHead,sourceInputsSha256}` with absolute paths. `kind=p12-c-runtime` selects only the historical P12-C files and PostgreSQL initialization file from the preserved R0 source. `kind=p12-d-operations` selects the complete D controller, imported runners, restoration SQL, PowerShell helpers and their C runtime/template/network dependencies. The helper copies those exact bytes into `outputRoot/<content SHA256>` and returns their sorted path/size/SHA inventory. The digest excludes target paths, timestamps and release labels. Missing, altered, linked or extra files fail validation. Files are made read-only, and their hashes are checked again when consumed.

Every request additionally supplies `workspaceRoot` and `controllerPayload` containing the complete returned D record. Every manifest supplies `artifacts.operationalPayload` containing the selected release's returned record; its source release/head/input SHA must match the manifest. R0 has historical C files and no fictional D entrypoint. The common D safety controller is identified separately and may remain active during R0 rollback. R1's operational payload can be the same retained D record as the controller. Product JAR/dist contents can remain equal; that equality and actual operational differences are recorded by the integration harness.

The CLI validates the retained controller and launches its actual retained entrypoint. All operational imports, restoration SQL, Compose files, templates and initialization scripts are loaded from retained payloads. No mutable worktree file supplies a runtime dependency. `execution-payload.json` records the executing entrypoint/PID, actual loaded dependency paths and SHA values, selected runtime payload provenance, restoration SQL and generated configuration hashes. `after-invalidation.json` connects the pinned SQL to affected counts and the before/after database comparison. Release pointers include the selected runtime payload and common controller digests. A separate read-only development proxy can be launched from the retained `Start-LocalReleaseProxy.ps1` with explicit `-WorkspaceRoot`, reusing private TLS/invitation files without putting a password on the CLI.

Run `node infra/p12-d/release-operations.cjs <absolute-request.json>` or, from PowerShell 7, `./infra/p12-d/Invoke-ReleaseOperation.ps1 -Request <absolute-request.json>`. The exit code and the fresh evidence directory's `result.json` must both indicate success. `phases.jsonl` records each finite infrastructure phase, its deadline and observed result. No scenario or overall runtime cap is imposed. A failure retains the exact target, partial files and evidence; traffic is not automatically reopened. There is no implicit cleanup command.

Database/API readiness each has one absolute 30-second phase. Expected not-ready attempts are progress observations inside that same deadline, retaining their original exit codes and redacted command logs; they never publish a failed phase end or reset the deadline. Only definitive readiness publishes one successful end. Persistent not-ready, a hard command error or a deadline expiry publishes one failed/timeout end and blocks the next operation. PostgreSQL readiness uses authenticated loopback TCP to the final server and confirms the explicit new database; the container reads its existing administrator password internally, without putting its value on a CLI or in metadata. The temporary socket-only initialization server cannot pass that probe. Host API/worker startup reports progress under explicit readiness phases, so an inner spawn observation cannot close an unrelated outer phase.

The request shape is:

```json
{
  "action": "Prepare",
  "commandId": "<new UUID>",
  "runId": "<target ownership UUID>",
  "evidenceDirectory": "D:/Workspace/ai-portfolio-practice/.local-tools/<new evidence>",
  "manifestPath": "D:/Workspace/ai-portfolio-practice/.local-tools/<release>/manifest.json",
  "expectedReleaseId": "NONE",
  "target": {
    "project": "p12d-<unique lowercase suffix>",
    "container": "p12d-postgres-<unique lowercase suffix>",
    "database": "d2_it_<32 lowercase UUID hex digits>",
    "httpsPort": 39481,
    "postgresPort": 39482,
    "apiPort": 39483,
    "root": "D:/Workspace/ai-portfolio-practice/.local-tools/<new stack>",
    "mediaPath": "D:/Workspace/ai-portfolio-practice/.local-tools/<new stack>/media"
  }
}
```

`Prepare` and `Restore` require a nonexistent target root, unused project/container/volume, and `expectedReleaseId=NONE`. Other commands require the original `runId`, unchanged complete `target`, and the actual active release ID. `postgresPort` is optional for public Compose mode; it and `apiPort` are mandatory for the local JAR mode. Every published port binds `127.0.0.1`. The dedicated media child prevents overlap with configuration, retained releases or static assets. Existing evidence paths, junctions, links, traversal, old database names and implicit defaults are rejected. Same command ID/input can return a completed result only after every recorded output hash is verified; changing its input fails. Reuse of an incomplete request fails rather than promoting partial work. A project lock is never automatically declared stale or removed by a second invocation.

Create a manifest with `node infra/p12-d/create-release-manifest.cjs <definition.json> <new-manifest.json>`. The definition supplies the following fields; the helper computes JAR/config/migration hashes and a complete dist inventory. It does not infer source identity or compatibility:

```json
{
  "releaseId": "R0",
  "source": {"head": "<40 hex commit>", "inputsSha256": "<64 hex input manifest hash>"},
  "artifacts": {
    "image": "sha256:<64 hex cached image ID>",
    "jar": {"path": "<absolute preserved JAR>"},
    "dist": {"path": "<absolute production dist>"},
    "nginxConfig": {"path": "<absolute rendered nginx.conf>"},
    "proxyConfig": {"path": "<absolute rendered package-proxy.conf>"},
    "migrations": [{"version": "1", "path": "<absolute V1 SQL>"}]
  },
  "runtime": {
    "mode": "public-demo", "execution": "compose", "access": "invited-only",
    "origin": "https://localhost", "phoneProvider": "disabled",
    "applicationEnvironment": {},
    "powershellPath": "<absolute cached PowerShell 7 executable>",
    "secretsPath": "<absolute folder with tls.crt, tls.key, reviewers>",
    "environmentFile": "<absolute private DB credentials file>"
  },
  "compatibility": {"schemaVersion": "132", "apiReleaseIds": ["R0", "R1"], "uiReleaseIds": ["R0", "R1"]},
  "readiness": {"sql": "SELECT count(*) FROM marketplace.media_namespace", "expected": "1"}
}
```

Supply **all** real migrations for that artifact, including historical versions. The prepared image's `/app/marketplace.jar` SHA must equal the supplied JAR. API and worker use that same immutable image/JAR. Activation checks actual Flyway version, every successful history entry and its source CRC32, then the read-only business probe, and verifies running identities before opening ingress. The business SQL runs in a read-only transaction. A health response alone never makes a release READY.

The private environment file accepts only `PACKAGE_DB_USER`, `PACKAGE_DB_PASSWORD`, and `PACKAGE_DB_ADMIN_PASSWORD` as literal `NAME=value` lines. App and administrator passwords must be distinct and at least 16 characters; the app role cannot be postgres. Values never enter request JSON, manifest, CLI arguments or retained textual logs. Do not place this file or checkpoint dumps inside dist. TLS/invitation files remain private mounted inputs.

P uses actual full Compose: `runtime.mode=public-demo`, `execution=compose`, `phoneProvider=disabled`, and no emulator variables. L uses `mode=local-full`, `execution=host-jar`, `phoneProvider=local-test`, an explicit cached `javaPath`, and `applicationEnvironment` with `FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:<owned emulator port>`, `GCLOUD_PROJECT=demo-<isolated project>`, and `MARKETPLACE_PHONE_MODE=local-test`. L retains the application's loopback and phone guards: host API and non-web worker are owned child processes, with PostgreSQL and TLS NGINX in new Docker resources. The caller owns the isolated emulator lifetime. P never inherits ambient emulator, Spring, Toss or application settings. The logical public origin is `https://localhost` because the accepted public guard forbids an explicit origin port; the proxy's forwarded port uses the actual isolated HTTPS port.

An explicit boolean `runtime.localMailbox=true` additionally selects the existing `db,local-mailbox` profile for the L host API only, so local verification/password-reset requests use the real DB token consumers and in-memory local mailbox. False or absent keeps the API's `db` profile. The worker always keeps `db,worker`. Public mode rejects true, and non-boolean values or arbitrary `SPRING_PROFILES_ACTIVE` environment input are rejected. The process record stores `profiles` and the exact verified invocation. This flag changes no application guard, token assertion or external issuer configuration; a consumed old token must still reach the actual token lookup and return400 rather than being counted as an unavailable503 result.

Both proxy files must be exact renderings of the existing P12-C templates: host `localhost`, external port `target.httpsPort`, upstream `api:8080` for P or `host.docker.internal:<apiPort>` for L. Existing P12-C subnet selection excludes current host routes and Docker networks before creating new private /28 networks; data is internal and the edge bridge permits explicit loopback publishing, matching P12-C. When a PostgreSQL observation port is explicitly requested, only that new PostgreSQL container also joins the edge bridge. No Docker defaults or existing networks are modified. Static assets for every retained release are merged by immutable path/hash; collisions fail. The index and release pointer are atomically published only after readiness. Old assets and old release artifacts are retained.

`Checkpoint` closes ingress, stops all owned API/worker writers, confirms their process/container identities are stopped, and rejects **any other DB connection**. It hashes a canonical table/sequence inventory, validates media namespace and all live/referenced files, runs custom-format `pg_dump` and `pg_restore --list`, copies media excluding `.lock`, then repeats the source inventories and connection check. Only a fully consistent result gets `checkpoint.json`, published last. Unreferenced bytes are classified and preserved. A failed dump/copy remains `.partial`.

`Restore` additionally requires `checkpointPath` pointing to a completed checkpoint. Its project/DB/run/media identity must differ from the checkpoint source. The dump, media hashes and quiescence evidence are validated before target creation. Strict `pg_restore --exit-on-error` restores into the new DB without `--clean`, then the complete original and restored inventories must match. The shared `Invalidate-RestoredCredentials.sql` runs only on the verified new target after that comparison; the caller sets verified database/run GUCs on the same connection. Its affected counts and allowed authentication-table differences are retained in `after-invalidation.json`. Business tables, account state, owner relationships, passwords, histories and media are preserved. Worker/ingress remain closed until a separate `Activate`.

`Rollback` uses the explicit retained older manifest, stops the new writers, retains the current DB/media, and checks the supported API/UI matrix. It compares the canonical DB before/after old API readiness, so it cannot silently perform data rollback. Corruption/incompatible migration requires a separate `Restore` into another new target. `Inspect` reads actual target identity, pointer and container state and writes only new evidence; it does not modify target state.

The local focused command is `node --test verification/Test-ReleaseOperations.cjs`; its contract evidence directory is printed. These guard checks do not replace registered fresh PostgreSQL, producer/ledger, credential, old/new UI, worker-failure and full Compose assertions, or the final unselected Offline harness required by P12-D.

The harness failure finalizer reuses the retained host adapter's internal `--stop-owned <request.json>` entry. This is not another release action or a web API. It requires the unchanged target ownership/run record, retained controller, manifest and a new evidence directory, plus the caller's pre-launch `cleanupDeadlineEpochMs`. Both roles share the earlier of that absolute deadline and 10 seconds from cleanup start; command drain time is reserved inside that budget. The adapter verifies PID creation, exact parsed JVM/JAR arguments, JAR bytes and workspace ownership immediately before stopping a process tree. A changed PID/creation/command cannot be killed. Every role is attempted independently, termination is observed through the OS, and any identity/cleanup failure blocks success while preserving the audit. Host JVM startup keeps its existing detached, file-backed I/O; a synthetic JVM regression verifies that the launcher exits while both roles run, then confirms root and descendant termination. No original rehearsal process is used by that regression.

After OS-observed absence, the host adapter retains an immutable termination receipt containing the original run/command cause, PID, creation time, JVM invocation and JAR hash. The current role record atomically references that receipt and marks that incarnation stopped. Later inspection/start/cleanup verifies the receipt and treats the original incarnation as stopped; a numeric PID subsequently reused by another process is never selected for a signal. Idempotent cleanup keeps the original stop cause. A pending or live record still requires its exact current identity, and a missing, altered or incorrectly bound terminal receipt fails closed. A new startup publishes a fresh verified incarnation rather than inheriting the prior receipt.

`rollback-preservation.json` records both complete samples at the actual quiet boundary: after all writers stop, then after the selected API is ready and before its worker starts. It retains exact table count/MD5 comparisons and adds every full-row SHA256, sequence values, media inventory and actual API/worker identity checks. A mismatch writes failed proof and blocks worker/ingress activation. Table samples use one repeatable-read/read-only transaction with UTC and ISO YMD date formatting. Each table has `{count,md5,rowHashes}`; `rowHashes` contains SHA256 of the UTF-8 PostgreSQL `row_to_json(t)::text`, ordered by that complete row text. No private row values are emitted. `__sequences` is the name-sorted `{name,value}` list; values are decimal strings or null so large bigint sequence values retain precision. Media includes namespace, complete files/path/size/SHA, references and unreferenced paths, excluding the process `.lock` only.

The shared `snapshotSha256(database,media)` digest hashes recursively sorted `{database,media:{namespace,files,referenced,unreferenced}}`, excluding a checkpoint copy path. A completed checkpoint records this digest and its command ID. Restoration verifies that digest and records the identical pre-invalidation digest and original checkpoint manifest SHA. Rollback proof uses the same before/after digest, plus command/run/release/manifest/payload identities and the actual process observations. These are quiet-boundary proofs; they do not claim equality after a newly started worker legitimately advances its durable state.

The post-API-ready/pre-worker rollback sample also retains an owner-private JSONL artifact. An empty file receives a verified owner-only ACL before any database byte is written; the runner sends psql output directly to that secured descriptor and closes it, without putting raw rows in stdout buffers, progress or command logs. Each table line contains `{table,count,md5,rowHashes,canonicalRows,rows}` from the same repeatable-read UTC/ISO transaction. `canonicalRows` preserves the complete PostgreSQL `row_to_json` text as strings, including bigint/decimal representation; `rows` contains the corresponding typed `to_jsonb` objects. Node verifies canonical byte hashes, aggregate MD5 and counts. The private Java reader must additionally bind each canonical row to its typed object using precision-preserving JSON before causal validation. Parse failures use a generic message without private data.

Only an artifact reference (`path`, file SHA256, size, row encoding, observed time, pre-write ACL evidence and the exact quiet snapshot SHA) is added to the public proof's `after.privateRows`. `after.sequenceDiagnostics` separately records sequence start/increment/cache/cycle, physical `lastValue`, `isCalled`, canonical value, observation role and SELECT/USAGE rights. All integer values remain strings. These diagnostics do not change canonical `__sequences` or the full exact guard. The baseline therefore refers to the actual preserved quiet sample, and subsequent scheduled worker effects still require the integration harness's exact producer/claim/metric/session validators.
