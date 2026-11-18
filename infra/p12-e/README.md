# Independent local container reproduction

This Windows-only proof reuses the P12-C Compose package and its immutable image pins. It creates a new Compose project, explicit nonoverlapping private networks, PostgreSQL volume and media volume. API and worker use the same staged JAR/image. Only the TLS proxy publishes an owned loopback port. It never downloads, deletes an old resource, or executes an external payment.

Prerequisites are Docker Desktop with Compose 2.24 or newer (`!override` support), the explicit stable PowerShell 7.6.9 executable with native `Get-NetRoute`, JDK 21 (`java`, `javac`, `jar`), Node 24, lockfile-installed `frontend/node_modules`, installed Playwright Chromium, and the cached pinned PostgreSQL/NGINX/Temurin images declared by `../p12-c/compose.yaml` and `../p12-c/Dockerfile`. Missing tools/images fail; they are not skipped. Choose absolute tool and cache locations on the reader's machine. No personal `.local-tools` artifact path is built into these scripts. PowerShell uses the declared, user-approved `C:/Users/rihee/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe` only; its actual path/version are checked in each child. Other versions/paths fail without installation or PATH fallback. NetTCPIP is loaded natively with `-SkipEditionCheck`; Windows PowerShell compatibility sessions are rejected.

From the repository root, either supply already verified artifacts and their manifests, or build fresh artifacts with:

```powershell
& '<absolute-node.exe>' infra/p12-e/prepare-artifacts.cjs '<absolute-build-request.json>'
```

The build request has absolute `output` (new directory), `jdk`, `node`, `npmCli` (installed npm `bin/npm-cli.js`), `mavenHome` (installed Maven 3.9.16), and `mavenUserHome` (contains the prepared `repository` cache). Maven runs offline with tests skipped solely to produce the artifact; Vite produces a production bundle. This is **not a verification pass**. Logs, source hashes, JAR hash and every bundle file hash produce `package-proof.json` and `package-dist.json`. Build/cache preparation and dependency acquisition are explicit operator prerequisites; the script does not install them. A missing offline Maven dependency fails and leaves its output.

Then run:

```powershell
& '<absolute-node.exe>' infra/p12-e/reproduce.cjs '<absolute-reproduction-request.json>'
```

The reproduction request requires eight absolute paths: `evidence` (must not exist), `jar`, `dist`, `jdk`, `node`, `browsers`, `jarProvenance`, and `distProvenance`. Fresh producer output supplies `marketplace.jar`, `dist/`, `package-proof.json`, `package-dist.json`. Alternatively, retained accepted D package artifacts and their exact manifests are valid declared inputs; they are copied into the new evidence directory and checked before use. An example shape, with placeholders that must be replaced before execution:

```json
{
  "evidence": "D:/proofs/new-unique-run",
  "jar": "D:/prepared/marketplace.jar",
  "dist": "D:/prepared/dist",
  "jdk": "D:/tools/jdk-21",
  "node": "D:/tools/node/node.exe",
  "browsers": "D:/tools/playwright",
  "jarProvenance": "D:/prepared/package-proof.json",
  "distProvenance": "D:/prepared/package-dist.json"
}
```

The one-shot `PrepareAccounts.java` is compiled against the declared production JAR classes/libraries and runs inside a new container. It creates only individually verified synthetic accounts and one audited initial administrator through existing application contracts. It creates no store/product/order, grants no seller role, and exposes no HTTP test endpoint. Its local mailbox closes before `public-demo` API startup. Seller application and operator approval, buyer quote/order/mock payment, seller shipment, and buyer follow-up run through real UI/API commands. Category/store/product/inventory preparation is explicitly ordinary API setup. PostgreSQL observation reads the same server-produced order ID, amount, reservation and shipment; the autonomous worker produces the notification/shipment consumer. Guest commands remain503 and test endpoints remain unavailable in public mode.

Each business command uses the existing shared10-second deadline, including response, reread and assertions. Infrastructure preparation/startup and cleanup use finite declared boundaries. There is no scenario or overall suite cap; a parent watchdog supervises individual phase completion and progress gaps. A failure blocks `result.status=passed`, including cleanup or preservation failure. The driver stops only its owned project/preparation container and retains failed/stopped containers, networks, volumes, logs and proof files. It does not run `down -v`.

Review `result.json`, `migration.json`, `preservation.json`, `proof-source.json`, `browser-report.json`, `browser-deadlines.json`, and both `desktop-business.json`/`mobile-business.json`. A successful browser report has exactly the required desktop/mobile business tests, no skip/retry/failure, and passed deadline evidence. Private account/gate files, TLS keys and `*.private.png` originals remain local evidence. The masked PNG derivatives have separate manifests with original/derivative hashes, capture time, viewport and path. Publish only reviewed safe derivatives; screenshots do not substitute for transaction assertions.

Focused guard checks (new directories and retained sentinels, missing input refusal, subnet overlap, preservation mutation/ownership failure cases):

```powershell
& '<absolute-node.exe>' --test infra/p12-e/guards.test.cjs infra/p12-e/preservation.test.cjs
```

The integrated harness also owns the mandatory reproduction phase and required browser names; standalone proof is development evidence. See the current P12-E work record for exact source, commands, failures and accepted verification evidence. This recipe does not claim real Toss, real remittance, public deployment or a P12-wide milestone pass.
