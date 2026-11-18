package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real operator commands -> retained/current UI -> API -> isolated PostgreSQL/media. */
@ExtendWith(ContractTiming.class)
class ReleaseRecoveryIT {
    private final ReleaseContractSupport support=new ReleaseContractSupport();
    private final ReleaseOperationLifecycle operationLifecycle=new ReleaseOperationLifecycle(support.folder);
    private final String node=support.root.resolve(".local-tools/node-v24.22.0-win-x64/node.exe").toString();
    private final String pwsh="C:/Users/rihee/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe";
    private final String gate=UUID.randomUUID().toString(),observerKey=UUID.randomUUID().toString();
    private final String user="marketplace_test",password=UUID.randomUUID().toString(),adminPassword=UUID.randomUUID().toString();
    private final List<Stack> stacks=new ArrayList<>();
    private final List<Stack> failedTargets=new ArrayList<>();
    private final List<HttpServer> observers=new ArrayList<>();
    private final List<String> extraContainers=new ArrayList<>();
    private final java.util.Set<Integer> allocatedPorts=new java.util.HashSet<>();
    private ReleasePortPool portPool;
    private Path r0jar,r1jar,r0dist,r1dist,devdist,r0Source;
    private String r0image,r1image,sourceInputs;
    private Map<String,Object> controllerPayload,r0Payload;

    @Test void actualReleaseCommandsExchangeRestoreAndReopenIsolatedLocalAndPublicStacks() throws Exception {
        Throwable primaryFailure=null;
        try {
            preparePortPool();
            prepareArtifacts();
            Stack local=create("L",false,true),localRestore=create("L",true,false);
            Files.writeString(support.folder.resolve("execution-identity.json"),support.json.writeValueAsString(Map.of("pid",ProcessHandle.current().pid(),"httpPort",local.apiPort,
                    "database",local.database,"jdbcUrl",local.url(),"media",local.media.toString())));
            prepareSource(local);
            browser("L",local,localRestore);
            // The public stack is prepared only after every owned local-full writer is stopped.
            stopStack(local);stopStack(localRestore);
            support.passwords.clear();support.accounts.clear();
            Stack publicStack=create("P",false,true),publicRestore=create("P",true,false);
            prepareSource(publicStack);
            browser("P",publicStack,publicRestore);
            stopStack(publicStack);stopStack(publicRestore);
            operatorFaults(local,publicStack);
            SharedProductionBundle.verifyIfDeclared(support.root, support.folder, r1dist);
            var lifecycle=new LinkedHashMap<String,Object>();
            lifecycle.put("sourceDatabase",local.database);lifecycle.put("sourceMedia",local.media.toString());
            lifecycle.put("stacks",stacks.stream().map(Stack::identity).toList());
            lifecycle.put("failedRestoreTargets",failedTargets.stream().map(Stack::identity).toList());
            lifecycle.put("r0JarSha256",ReleaseContractSupport.sha(r0jar));lifecycle.put("r1JarSha256",ReleaseContractSupport.sha(r1jar));
            lifecycle.put("r0ProductionDist",r0dist.toString());lifecycle.put("r1ProductionDist",r1dist.toString());
            lifecycle.put("localGuestDevelopmentDist",devdist.toString());lifecycle.put("originalResourcesPreserved",true);
            Files.writeString(support.folder.resolve("release-lifecycle.json"),support.json.writeValueAsString(lifecycle));
        } catch(Exception|AssertionError failure) {primaryFailure=failure;throw failure;}
        finally {
            var cleanupFailures=new ArrayList<Exception>();
            operationLifecycle.stopAccepting();
            for(var server:observers) {server.stop(0);}
            boolean operationJoinPassed=false;
            try {operationLifecycle.cancelAndJoin();operationJoinPassed=true;}catch(Exception|AssertionError failure) {cleanupFailures.add(new IllegalStateException("Owned operator cancellation/join failed; target cleanup was withheld",failure));}
            var owned=new ArrayList<Stack>(stacks);owned.addAll(failedTargets);
            if(operationJoinPassed) {
                for(var stack:owned) {try {stopOwnedOnFailure(stack);}catch(Exception failure) {cleanupFailures.add(failure);Files.writeString(support.folder.resolve("cleanup-failed-"+stack.project+".txt"),failure.getClass().getSimpleName());}}
                for(String container:extraContainers) {try {support.command(support.folder.resolve("extra-stop-"+container+".txt"),List.of("docker","stop","--timeout","5",container),Map.of(),10);}catch(Exception failure) {cleanupFailures.add(failure);}}
            }
            try {support.close();}catch(Exception failure) {cleanupFailures.add(failure);}
            if(!cleanupFailures.isEmpty()) {
                var cleanup=new IllegalStateException("Owned cleanup did not verify every child exit; inspect preserved cleanup evidence");cleanupFailures.forEach(cleanup::addSuppressed);
                if(primaryFailure!=null) {primaryFailure.addSuppressed(cleanup);}else {throw cleanup;}
            }
        }
    }

    private void preparePortPool() throws Exception {
        String observationId=UUID.randomUUID().toString();Path directory=support.folder.resolve("port-observation-"+observationId);
        long deadline=System.currentTimeMillis()+60000;
        support.command(support.folder.resolve("port-observation-"+observationId+".log"),List.of(pwsh,"-NoProfile","-File",
                support.root.resolve("verification/Observe-ReleasePortRanges.ps1").toString(),"-EvidenceDirectory",directory.toString(),
                "-ObservationId",observationId,"-DeadlineEpochMs",Long.toString(deadline)),Map.of(),60);
        Path report=directory.resolve("observation.json");portPool=ReleasePortPool.fromObservation(support.json.readTree(Files.readString(report)),observationId);
        Files.writeString(directory.resolve("eligible-pool.json"),support.json.writeValueAsString(Map.of("observationSha256",ReleaseContractSupport.sha(report),
                "intervals",portPool.intervals(),"size",portPool.size(),"attemptLimit",100,"historicalCollisionOwner","unresolved")));
    }

    private void prepareArtifacts() throws Exception {
        r0jar=ReleaseContractSupport.requiredPath("P12_D_R0_JAR");r0dist=ReleaseContractSupport.requiredPath("P12_D_R0_DIST");
        Path history=ReleaseContractSupport.requiredPath("P12_D_HISTORY_PROOF");var provenance=support.json.readTree(Files.readString(history));
        var jarRecords=provenance.path("runs").valueStream().filter(record->record.path("label").asText().equals("R0")&&record.has("jar")).toList();
        var distRecords=provenance.path("runs").valueStream().filter(record->record.path("label").asText().equals("R0")&&record.has("dist")).toList();
        assertEquals(1,jarRecords.size());assertEquals(1,distRecords.size());
        assertEquals("8bf6b8f50514d3251a8e0c820fda60237c6bb261",jarRecords.getFirst().path("commit").asText());
        assertEquals(r0jar.toAbsolutePath().normalize(),Path.of(jarRecords.getFirst().path("jar").asText()).toAbsolutePath().normalize());
        assertEquals(ReleaseContractSupport.sha(r0jar),jarRecords.getFirst().path("jarSha256").asText());
        assertEquals(r0dist.toAbsolutePath().normalize(),Path.of(distRecords.getFirst().path("dist").asText()).toAbsolutePath().normalize());
        r0Source=r0jar.getParent().getParent().getParent();
        var classes=Path.of(MarketplaceApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        r1jar=classes.getParent().resolve("marketplace-api-0.0.1-SNAPSHOT.jar");assertTrue(Files.isRegularFile(r1jar));
        var inputs=new ArrayList<Map<String,Object>>();
        for(String base:List.of("backend/src/main","frontend/src","infra/p12-c","infra/p12-d","verification")) {
            try(var paths=Files.walk(support.root.resolve(base))) {for(var file:paths.filter(Files::isRegularFile).sorted().toList()) {
                inputs.add(Map.of("path",support.root.relativize(file).toString().replace('\\','/'),"sha256",ReleaseContractSupport.sha(file)));
            }}
        }
        Path inputFile=support.folder.resolve("candidate-inputs.json");Files.writeString(inputFile,support.json.writeValueAsString(inputs));sourceInputs=ReleaseContractSupport.sha(inputFile);
        r1dist=buildDist("production",support.folder.resolve("dist-R1"));devdist=buildDist("development",support.folder.resolve("dist-guest-development"));
        r0image=image(r0jar,"R0");r1image=image(r1jar,"R1");
        controllerPayload=payload(support.root,"p12-d-operations","R1");
        r0Payload=payload(r0Source,"p12-c-runtime","R0");
        var oldEntries=applicationEntries(r0jar);var candidateEntries=applicationEntries(r1jar);
        var oldClasses=new java.util.TreeMap<>(oldEntries);oldClasses.keySet().removeIf(name->!name.endsWith(".class"));
        var candidateClasses=new java.util.TreeMap<>(candidateEntries);candidateClasses.keySet().removeIf(name->!name.endsWith(".class"));
        Files.writeString(support.folder.resolve("release-artifacts.json"),support.json.writeValueAsString(Map.of("R0",Map.of("jar",r0jar.toString(),"sha256",ReleaseContractSupport.sha(r0jar),"dist",r0dist.toString(),"files",inventory(r0dist)),
                "R1",Map.of("jar",r1jar.toString(),"sha256",ReleaseContractSupport.sha(r1jar),"dist",r1dist.toString(),"files",inventory(r1dist)),
                "localGuest",Map.of("mode","development","dist",devdist.toString(),"files",inventory(devdist)),"candidateInputs",inputFile.toString(),"candidateInputsSha256",sourceInputs,
                "applicationEntryComparison",Map.of("R0",oldEntries,"R1",candidateEntries,"identical",oldEntries.equals(candidateEntries),"classEntriesIdentical",oldClasses.equals(candidateClasses),
                        "resourceEntriesIdenticalAfterTextEolNormalization",normalizedResourceEntries(r0jar).equals(normalizedResourceEntries(r1jar))),"controllerPayload",controllerPayload,"R0Payload",r0Payload)));
    }

    private Map<String,String> applicationEntries(Path file) throws Exception {
        var entries=new java.util.TreeMap<String,String>();
        try(var jar=new java.util.jar.JarFile(file.toFile())) {
            for(var entry:jar.stream().filter(item->!item.isDirectory()&&item.getName().startsWith("BOOT-INF/classes/")).toList()) {
                try(var bytes=jar.getInputStream(entry)) {entries.put(entry.getName(),java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())));}
            }
        }
        return entries;
    }

    private Map<String,String> normalizedResourceEntries(Path file) throws Exception {
        var entries=new java.util.TreeMap<String,String>();
        try(var jar=new java.util.jar.JarFile(file.toFile())) {
            for(var entry:jar.stream().filter(item->!item.isDirectory()&&item.getName().startsWith("BOOT-INF/classes/")&&!item.getName().endsWith(".class")).toList()) {
                try(var input=jar.getInputStream(entry)) {
                    byte[] bytes=input.readAllBytes();String name=entry.getName();
                    if(List.of(".sql",".properties",".json",".xml",".yml",".yaml",".txt",".imports").stream().anyMatch(name::endsWith)) {
                        bytes=new String(bytes,StandardCharsets.UTF_8).replace("\r\n","\n").replace("\r","\n").getBytes(StandardCharsets.UTF_8);
                    }
                    entries.put(name,digest(bytes));
                }
            }
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> payload(Path source,String kind,String release) throws Exception {
        Path definition=support.folder.resolve("payload-definition-"+release+".json"),record=support.folder.resolve("payload-record-"+release+".json");
        Files.writeString(definition,support.json.writeValueAsString(Map.of("sourceRoot",source.toString(),"outputRoot",support.folder.resolve("payloads-"+release).toString(),"kind",kind,"sourceReleaseId",release,
                "sourceHead","8bf6b8f50514d3251a8e0c820fda60237c6bb261","sourceInputsSha256",release.equals("R0")?ReleaseContractSupport.sha(ReleaseContractSupport.requiredPath("P12_D_HISTORY_PROOF")):sourceInputs)));
        support.command(support.folder.resolve("payload-prepare-"+release+".log"),List.of(node,support.root.resolve("infra/p12-d/payload.cjs").toString(),definition.toString(),record.toString()),Map.of(),10);
        return support.json.readValue(Files.readString(record),Map.class);
    }

    private int isolatedPort() throws Exception {
        assertTrue(portPool!=null,"Fresh current port observation must precede all stack allocation");
        return isolatedPort(portPool,allocatedPorts,()->java.util.concurrent.ThreadLocalRandom.current().nextInt(portPool.size()));
    }
    static int isolatedPort(ReleasePortPool pool,java.util.Set<Integer> allocatedPorts,java.util.function.IntSupplier index) throws Exception {
        for(int attempt=0;attempt<100;attempt++) {
            int candidate=pool.portAt(index.getAsInt());
            if(allocatedPorts.contains(candidate)) {continue;}
            try(var socket=new java.net.ServerSocket()) {
                socket.bind(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),candidate));allocatedPorts.add(candidate);return candidate;
            } catch(java.net.BindException unavailable) { /* Preserve occupied ports and choose another new candidate. */ }
        }
        throw new IllegalStateException("No fresh isolated loopback port available");
    }

    private Path buildDist(String mode,Path output) throws Exception {
        if (mode.equals("production") && SharedProductionBundle.copyIfDeclared(support.root,support.folder,output)) {
            return output;
        }
        var builder=new ProcessBuilder(node,"node_modules/vite/bin/vite.js","build","--mode",mode,"--manifest","--outDir",output.toString())
                .directory(support.root.resolve("frontend").toFile()).redirectErrorStream(true).redirectOutput(support.folder.resolve("dist-"+mode+".txt").toFile());
        builder.environment().put("NODE_ENV",mode);var child=builder.start();support.children.add(child);
        if(!child.waitFor(60,TimeUnit.SECONDS)) {com.example.marketplace.jdbcfixture.BrowserChildWatchdog.terminate(child);throw new AssertionError("Finite dist build exceeded preparation phase");}
        assertEquals(0,child.exitValue());return output;
    }

    private String image(Path jar,String release) throws Exception {
        Path context=Files.createDirectory(support.folder.resolve("image-"+release));Files.copy(jar,context.resolve("marketplace.jar"));Files.copy(support.root.resolve("infra/p12-c/Dockerfile"),context.resolve("Dockerfile"));
        String tag="p12d-local:"+UUID.randomUUID().toString().replace("-","");
        support.command(support.folder.resolve("image-build-"+release+".txt"),List.of("docker","build","--pull=false","--network=none","--tag",tag,context.toString()),Map.of(),60);
        Path proof=support.folder.resolve("image-"+release+".json");support.command(proof,List.of("docker","image","inspect",tag),Map.of(),10);
        return support.json.readTree(Files.readString(proof)).get(0).path("Id").asText();
    }

    private List<Map<String,Object>> inventory(Path root) throws Exception {
        var files=new ArrayList<Map<String,Object>>();try(var walk=Files.walk(root)) {for(var file:walk.filter(Files::isRegularFile).sorted().toList()) {
            files.add(Map.of("path",root.relativize(file).toString().replace('\\','/'),"size",Files.size(file),"sha256",ReleaseContractSupport.sha(file)));
        }}return files;
    }

    private Stack create(String mode,boolean restore,boolean prepare) throws Exception {
        var stack=new Stack(mode,restore);stacks.add(stack);stack.configuration();
        if(prepare) {stack.operation("Prepare",stack.mode.equals("L")?"R0":"R1","NONE",null,null);System.out.println("D2_CREATED_DATABASE="+stack.database);}
        return stack;
    }

    private void prepareSource(Stack stack) throws Exception {
        support.accounts(stack.url(),user,password,stack.media,"132");
        stack.credentials=ReleaseCredentialPreparation.prepare(stack.url(),user,password,stack.media,support);
        if(stack.mode.equals("L")) {stack.reconciliation=ReleaseReconciliationFixture.prepare(stack.url(),user,password,stack.media,support.passwords,support.folder.resolve("reconciliation-producers.json"));}
        if(stack.mode.equals("L")) {ReleaseWorkerRecovery.exercise(support,r1jar,stack.url(),user,password,stack.media,stack.apiPort);}
        stack.operation("Activate",stack.mode.equals("L")?"R0":"R1","NONE",null,null);stack.active=stack.mode.equals("L")?"R0":"R1";
        if(stack.mode.equals("L")) {guestProxy(stack);}
        stack.observer();
    }

    @SuppressWarnings("unchecked")
    private void operatorFaults(Stack local,Stack publicStack) throws Exception {
        var localBefore=local.observeFull();var publicBefore=publicStack.observeFull();
        Path folder=Files.createDirectory(support.folder.resolve("mixed-checkpoint-fault"));
        var localCheckpoint=support.json.readValue(Files.readString(local.checkpoint),Map.class);
        var other=support.json.readValue(Files.readString(publicStack.checkpoint),Map.class);
        var mixed=ReleaseMixedCheckpoint.create(localCheckpoint,other);
        Files.copy(local.checkpoint.getParent().resolve("quiescence.json"),folder.resolve("quiescence.json"));
        Path checkpoint=folder.resolve("checkpoint.json");Files.writeString(checkpoint,support.json.writeValueAsString(mixed));
        var failed=new Stack("P",true);failedTargets.add(failed);failed.configuration();
        var outcome=failed.operation("Restore","R1","NONE",checkpoint,null,true);
        assertEquals("Media namespace mismatch",outcome.path("error").asText(),"The mixed checkpoint must fail specifically on DB/media namespace, after strict new-target restore");
        Path rejectedEvidence=support.folder.resolve("operation-Restore-"+outcome.path("commandId").asText());
        assertTrue(Files.readAllLines(rejectedEvidence.resolve("phases.jsonl")).stream().map(support.json::readTree).anyMatch(row->row.path("phase").asText().equals("strict-new-database-restore")&&row.path("state").asText().equals("completed")),"The actual strict pg_restore must complete before namespace refusal");
        String restoredNamespace=failed.jdbc().queryForObject("SELECT namespace::text FROM marketplace.media_namespace WHERE singleton",Map.of(),String.class);
        assertEquals(((Map<?,?>)localCheckpoint.get("media")).get("namespace"),restoredNamespace);
        assertFalse(restoredNamespace.equals(Files.readString(failed.media.resolve(".database"))),"Actual restored DB and copied media must have different namespaces");
        assertEquals(failed.database,failed.jdbc().queryForObject("SELECT current_database()",Map.of(),String.class));System.out.println("D2_CREATED_DATABASE="+failed.database);
        assertTrue(Files.exists(failed.root.resolve("ownership.json")));assertFalse(Files.exists(failed.root.resolve("active-release.json")));
        ReleaseSnapshotContract.exact(localBefore,local.observeFull());ReleaseSnapshotContract.exact(publicBefore,publicStack.observeFull());
        // Activate the retained R0 application with a deliberately incompatible schema declaration.
        // The real API starts and validates its database, then the command must keep ingress/worker closed.
        Path original=publicStack.manifests.get("R0");var bad=support.json.readValue(Files.readString(original),Map.class);
        ((Map<String,Object>)bad.get("compatibility")).put("schemaVersion","999");
        Path badFile=support.folder.resolve("fault-schema-declaration.json");Files.writeString(badFile,support.json.writeValueAsString(bad));publicStack.manifests.put("R0",badFile);
        var rejected=publicStack.operation("Activate","R0","R1",null,null,true);
        publicStack.manifests.put("R0",original);
        assertTrue(rejected.path("error").asText().contains("Schema version mismatch"));
        Path proof=support.folder.resolve("fault-maintenance-containers.json");support.command(proof,List.of("docker","ps","--filter","label=com.docker.compose.project="+publicStack.project,"--filter","status=running","--format",ReleaseMixedCheckpoint.RUNNING_SERVICES_FORMAT),Map.of(),10);
        assertFalse(Files.readAllLines(proof).stream().anyMatch(line->List.of("api","worker","proxy").contains(line.trim())));
        ReleaseSnapshotContract.exact(publicBefore,publicStack.observeFull());
        Files.writeString(support.folder.resolve("operator-faults.json"),support.json.writeValueAsString(Map.of("mixedCheckpoint",Map.of("target",failed.target(),"commandId",outcome.path("commandId").asText(),"error",outcome.path("error").asText(),"apiStarted",false,"sourceRowsUnchanged",true),
                "schemaReadiness",Map.of("targetProject",publicStack.project,"commandId",rejected.path("commandId").asText(),"error",rejected.path("error").asText(),"apiWorkerIngressRunning",false,"sourceRowsUnchanged",true))));
    }

    private void guestProxy(Stack stack) throws Exception {
        Path directory=support.folder.resolve("guest-proxy-"+stack.project);int port=isolatedPort();
        support.command(support.folder.resolve("guest-proxy-start.txt"),List.of(pwsh,"-NoProfile","-File",Path.of(controllerPayload.get("root").toString()).resolve("infra/p12-d/Start-LocalReleaseProxy.ps1").toString(),
                "-Evidence",directory.toString(),"-ApiPort",Integer.toString(stack.apiPort),"-HttpsPort",Integer.toString(port),"-SecretsPath",stack.configuration.resolve("secrets").toString(),"-RunId",stack.runId,"-Dist",devdist.toString(),"-WorkspaceRoot",support.root.toString()),Map.of(),60);
        var owner=support.json.readTree(Files.readString(directory.resolve("proxy-owner.json")));extraContainers.add(owner.path("container").asText());stack.guestBase="https://localhost:"+port;
    }

    private void browser(String mode,Stack source,Stack restore) throws Exception {
        if(restore.server==null) {restore.observer();}restore.source=source;
        Path evidence=Files.createDirectory(support.folder.resolve("browser-"+mode));
        var contract=new LinkedHashMap<String,Object>();contract.put("runId",source.runId);contract.put("mode",mode);contract.put("source",source.browserIdentity());contract.put("restore",restore.browserIdentity());
        contract.put("dist",Map.of("R0",r0dist.toString(),"R1",r1dist.toString()));contract.put("accounts",support.accounts);contract.put("passwords",support.passwords);
        if(mode.equals("L")) {contract.put("emulator",Map.of("host",System.getenv("FIREBASE_AUTH_EMULATOR_HOST"),"project",System.getenv("GCLOUD_PROJECT")));contract.put("credentials",source.credentials);contract.put("reconciliation",source.reconciliation);}
        Path contractFile=support.folder.resolve("browser-contract-"+mode+".private.json");Files.writeString(contractFile,support.json.writeValueAsString(contract));
        var builder=new ProcessBuilder(node,"node_modules/@playwright/test/cli.js","test","--config=auth-e2e/release-runtime.config.ts").directory(support.root.resolve("frontend").toFile())
                .redirectErrorStream(true).redirectOutput(evidence.resolve("browser.txt").toFile());
        var env=builder.environment();env.put("P12_D_BROWSER_CONTRACT",contractFile.toString());env.put("A3_EVIDENCE",evidence.toString());
        if(mode.equals("P")) {env.keySet().removeIf(key->key.startsWith("FIREBASE_")||key.startsWith("FIRESTORE_")||key.startsWith("GCLOUD_")||key.startsWith("TOSS_"));}
        var child=builder.start();support.children.add(child);com.example.marketplace.jdbcfixture.BrowserChildWatchdog.await(child,evidence.resolve("browser-progress.ndjson"));
        assertEquals(0,child.exitValue(),"Inspect preserved "+evidence.resolve("browser.txt"));
        ReleaseCredentialPreparation.assertPendingTokensPreserved(restore.jdbc());
    }

    private void stopStack(Stack stack) throws Exception {if(stack.active!=null) {stack.operation("Checkpoint",stack.active,stack.active,null,null);stack.active=null;}}
    private void stopHostRoles(Stack stack) throws Exception {
        String id=UUID.randomUUID().toString();Path request=support.folder.resolve("host-cleanup-"+id+".json");
        var input=new LinkedHashMap<String,Object>();input.put("workspaceRoot",support.root.toString());input.put("controllerPayload",controllerPayload);
        input.put("target",stack.target());input.put("runId",stack.runId);input.put("commandId",id);
        input.put("evidenceDirectory",support.folder.resolve("host-cleanup-evidence-"+id).toString());
        input.put("manifestPath",stack.manifests.get(stack.active==null?"R0":stack.active).toString());
        input.put("cleanupDeadlineEpochMs",System.currentTimeMillis()+10000);
        Files.writeString(request,support.json.writeValueAsString(input));
        support.command(support.folder.resolve("host-cleanup-"+id+".log"),List.of(node,Path.of(controllerPayload.get("root").toString()).resolve("infra/p12-d/host-runtime.cjs").toString(),"--stop-owned",request.toString()),Map.of("P12_D_WORKSPACE_ROOT",support.root.toString()),10);
    }
    private void stopOwnedOnFailure(Stack stack) throws Exception {
        Path state=stack.root.resolve("state.json");if(!Files.exists(state)) {return;}
        if(stack.mode.equals("L")) {
            stopHostRoles(stack);
        }
        Path log=support.folder.resolve("cleanup-"+stack.project+".txt");
        support.command(log,List.of("docker","ps","--filter","label=com.docker.compose.project="+stack.project,"--format","{{.ID}}"),Map.of(),10);
        for(String id:Files.readAllLines(log).stream().filter(line->line.matches("[0-9a-f]{12}")).toList()) {
            support.command(support.folder.resolve("cleanup-stop-"+id+".txt"),List.of("docker","stop","--timeout","5",id),Map.of(),10);
        }
        Path extras=support.folder.resolve("cleanup-extra-"+stack.project+".txt");
        support.command(extras,List.of("docker","ps","--filter","label=marketplace.p12d.run="+stack.runId,"--format","{{.ID}}"),Map.of(),10);
        for(String id:Files.readAllLines(extras).stream().filter(line->line.matches("[0-9a-f]{12}")).toList()) {
            support.command(support.folder.resolve("cleanup-extra-stop-"+id+".txt"),List.of("docker","stop","--timeout","5",id),Map.of(),10);
        }
    }

    private final class Stack {
        final String mode,runId=UUID.randomUUID().toString(),project="p12d-"+UUID.randomUUID().toString().replace("-","");
        final String database,container=project+"-postgres";
        final int httpsPort,postgresPort,apiPort;
        final Path root,media,configuration;
        final Map<String,Path> manifests=new LinkedHashMap<>();
        final Map<String,ReleaseSnapshotContract.Snapshot> snapshots=new LinkedHashMap<>(),checkpointSnapshots=new LinkedHashMap<>();
        final Map<String,Map<String,Object>> checkpointRefs=new LinkedHashMap<>();
        final Map<String,Path> checkpointPaths=new LinkedHashMap<>();
        final Map<String,ReleaseWorkerOrigin.Incarnation> snapshotWorkers=new LinkedHashMap<>();
        final ReleaseWorkerOrigin workerOrigin=new ReleaseWorkerOrigin();
        Path lastOperationEvidence;String latestCheckpointId;
        String active,guestBase;Path checkpoint;Stack source;HttpServer server;Map<String,String> credentials;Map<String,Object> reconciliation;
        Stack(String mode,boolean restore) throws Exception {
            this.mode=mode;database=!restore&&mode.equals("L")?System.getenv("D2_DB_NAME"):"d2_it_"+UUID.randomUUID().toString().replace("-","");
            httpsPort=isolatedPort();postgresPort=isolatedPort();apiPort=isolatedPort();
            root=support.folder.resolve("target-"+mode+"-"+(restore?"restore":"source")+"-"+project.substring(project.length()-8));media=root.resolve("media");configuration=Files.createDirectory(support.folder.resolve("configuration-"+project));
        }
        String url() {return "jdbc:postgresql://127.0.0.1:"+postgresPort+"/"+database;}
        NamedParameterJdbcTemplate jdbc() {return support.jdbc(url(),user,password);}
        Map<String,Object> target() {return Map.of("project",project,"database",database,"container",container,"httpsPort",httpsPort,"postgresPort",postgresPort,"apiPort",apiPort,"root",root.toString(),"mediaPath",media.toString());}
        Map<String,Object> identity() {return Map.of("mode",mode,"runId",runId,"target",target(),"manifests",manifests);}
        Map<String,Object> browserIdentity() {var value=new LinkedHashMap<String,Object>();value.put("baseURL","https://localhost:"+httpsPort);value.put("observerURL","http://127.0.0.1:"+server.getAddress().getPort());value.put("observerKey",observerKey);value.put("gateUser","reviewer");value.put("gatePassword",gate);if(guestBase!=null) {value.put("guestBaseURL",guestBase);}return value;}

        void configuration() throws Exception {
            Path secrets=configuration.resolve("secrets");Files.createDirectory(secrets);
            Path script=configuration.resolve("tls.ps1");
            Files.writeString(script,"$ErrorActionPreference='Stop'\n. '"+support.root.resolve("verification/PowerShellRuntime.ps1").toString().replace("'","''")+"'\n$null=Assert-VerificationPowerShellRuntime\n$rsa=[Security.Cryptography.RSA]::Create(2048)\ntry {$r=[Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=localhost',$rsa,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1); $s=[Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new();$s.AddDnsName('localhost');$s.AddIpAddress([Net.IPAddress]::Loopback);$r.CertificateExtensions.Add($s.Build());$c=$r.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-1),[DateTimeOffset]::UtcNow.AddDays(2));[IO.File]::WriteAllText($env:P12D_SECRETS+'/tls.crt',$c.ExportCertificatePem());[IO.File]::WriteAllText($env:P12D_SECRETS+'/tls.key',$rsa.ExportPkcs8PrivateKeyPem())}finally{$rsa.Dispose()}\n$h=[Convert]::ToBase64String([Security.Cryptography.SHA1]::HashData([Text.Encoding]::UTF8.GetBytes($env:P12D_GATE)));[IO.File]::WriteAllText($env:P12D_SECRETS+'/reviewers',\"reviewer:{SHA}$h`n\")\n");
            support.command(configuration.resolve("tls.txt"),List.of(pwsh,"-NoProfile","-File",script.toString()),Map.of("P12D_SECRETS",secrets.toString(),"P12D_GATE",gate),10);
            Path environment=configuration.resolve("database.private.env");Files.writeString(environment,"PACKAGE_DB_USER="+user+"\nPACKAGE_DB_PASSWORD="+password+"\nPACKAGE_DB_ADMIN_PASSWORD="+adminPassword+"\n");
            String upstream=mode.equals("L")?"host.docker.internal:"+apiPort:"api:8080";
            for(String name:List.of("nginx.conf","package-proxy.conf")) {Files.writeString(configuration.resolve(name),Files.readString(support.root.resolve("infra/p12-c/"+name+".template")).replace("__HOST__","localhost").replace("__PORT__",Integer.toString(httpsPort)).replace("__UPSTREAM__",upstream));}
            for(String release:List.of("R0","R1")) {
                Path jar=release.equals("R0")?r0jar:r1jar,dist=release.equals("R0")?r0dist:r1dist;
                var runtime=new LinkedHashMap<String,Object>();runtime.put("mode",mode.equals("L")?"local-full":"public-demo");runtime.put("execution",mode.equals("L")?"host-jar":"compose");runtime.put("access","invited-only");runtime.put("origin","https://localhost");runtime.put("phoneProvider",mode.equals("L")?"local-test":"disabled");runtime.put("powershellPath",pwsh);runtime.put("javaPath",Path.of(System.getProperty("java.home"),"bin","java.exe").toString());runtime.put("secretsPath",secrets.toString());runtime.put("environmentFile",environment.toString());
                runtime.put("localMailbox",mode.equals("L"));
                runtime.put("applicationEnvironment",mode.equals("L")?Map.of("FIREBASE_AUTH_EMULATOR_HOST",System.getenv("FIREBASE_AUTH_EMULATOR_HOST"),"GCLOUD_PROJECT",System.getenv("GCLOUD_PROJECT"),"MARKETPLACE_PHONE_MODE","local-test"):Map.of());
                var artifacts=new LinkedHashMap<String,Object>();artifacts.put("jar",Map.of("path",jar.toString(),"sha256",ReleaseContractSupport.sha(jar)));artifacts.put("image",release.equals("R0")?r0image:r1image);artifacts.put("dist",Map.of("path",dist.toString(),"files",inventory(dist)));artifacts.put("operationalPayload",release.equals("R0")?r0Payload:controllerPayload);
                artifacts.put("nginxConfig",Map.of("path",configuration.resolve("nginx.conf").toString(),"sha256",ReleaseContractSupport.sha(configuration.resolve("nginx.conf"))));artifacts.put("proxyConfig",Map.of("path",configuration.resolve("package-proxy.conf").toString(),"sha256",ReleaseContractSupport.sha(configuration.resolve("package-proxy.conf"))));
                Path migrationSource=release.equals("R0")?r0Source:support.root;
                var migrations=new ArrayList<Map<String,Object>>();try(var paths=Files.list(migrationSource.resolve("backend/src/main/resources/db/migration"))) {for(var file:paths.filter(Files::isRegularFile).sorted().toList()) {String name=file.getFileName().toString();migrations.add(Map.of("version",name.substring(1,name.indexOf("__")),"path",file.toString(),"sha256",ReleaseContractSupport.sha(file)));}}artifacts.put("migrations",migrations);
                var manifest=Map.of("releaseId",release,"source",Map.of("head","8bf6b8f50514d3251a8e0c820fda60237c6bb261","inputsSha256",release.equals("R0")?ReleaseContractSupport.sha(ReleaseContractSupport.requiredPath("P12_D_HISTORY_PROOF")):sourceInputs),"artifacts",artifacts,"runtime",runtime,"compatibility",Map.of("schemaVersion","132","apiReleaseIds",List.of("R0","R1"),"uiReleaseIds",List.of("R0","R1")),"readiness",Map.of("sql","SELECT CASE WHEN count(*)>=8 THEN 'ready' ELSE 'missing' END FROM marketplace.identity_user WHERE state='ACTIVE'","expected","ready"));
                Path file=configuration.resolve("manifest-"+release+".json");Files.writeString(file,support.json.writeValueAsString(manifest));manifests.put(release,file);
            }
        }

        JsonNode operation(String action,String release,String expected,Path from,HttpExchange stream) throws Exception {
            return operation(action,release,expected,from,stream,false);
        }
        JsonNode operation(String action,String release,String expected,Path from,HttpExchange stream,boolean expectedFailure) throws Exception {
            String commandId=UUID.randomUUID().toString();Path evidence=support.folder.resolve("operation-"+action+"-"+commandId);
            var previousStartupRuns=new TreeMap<String,java.util.Set<String>>();
            Map<String,Object> producerBaseline=Map.of();
            if(List.of("Activate","Rollback").contains(action)) {
                producerBaseline=dataPhase(stream,"inventory declared startup and due producers",this::producerInventory);
                workerOrigin.beginActivation();
                for(String family:ReleaseStartupPreparation.TYPES.keySet()) {
                    previousStartupRuns.put(family,new java.util.HashSet<>(jdbc().queryForList("SELECT id::text FROM marketplace.claim_"+family+"_recovery_run",Map.of(),String.class)));
                }
            }
            var request=new LinkedHashMap<String,Object>();request.put("action",action);request.put("commandId",commandId);request.put("runId",runId);request.put("evidenceDirectory",evidence.toString());request.put("manifestPath",manifests.get(release).toString());request.put("target",target());request.put("expectedReleaseId",expected);if(from!=null) {request.put("checkpointPath",from.toString());}
            request.put("workspaceRoot",support.root.toString());request.put("controllerPayload",controllerPayload);
            Path requestFile=support.folder.resolve("request-"+commandId+".json");Files.writeString(requestFile,support.json.writeValueAsString(request));
            var builder=new ProcessBuilder(node,support.root.resolve("infra/p12-d/release-operations.cjs").toString(),requestFile.toString()).directory(support.root.toFile()).redirectErrorStream(true).redirectOutput(support.folder.resolve("operation-"+commandId+".log").toFile());
            try(var ownedOperation=operationLifecycle.start(builder)) {
            var child=ownedOperation.process();support.children.add(child);int offset=0;var phases=new ReleasePhaseForwarder(System.currentTimeMillis(),System.nanoTime());
            while(child.isAlive()||Files.exists(evidence.resolve("phases.jsonl"))&&Files.readAllLines(evidence.resolve("phases.jsonl")).size()>offset) {
                var lines=Files.exists(evidence.resolve("phases.jsonl"))?Files.readAllLines(evidence.resolve("phases.jsonl")):List.<String>of();
                for(;offset<lines.size();offset++) {var event=support.json.readTree(lines.get(offset));for(var forward:phases.accept(event,System.currentTimeMillis(),System.nanoTime())) {emit(stream,forward);}}
                phases.check(System.nanoTime());
                Thread.sleep(50);
            }
            phases.finish();
            var result=support.json.readTree(Files.readString(evidence.resolve("result.json")));
            if(expectedFailure) {assertTrue(child.exitValue()!=0,"Expected operator fault must fail");assertEquals("failed",result.path("status").asText());return result;}
            assertEquals(0,child.exitValue(),"Inspect retained operation "+evidence);assertEquals("passed",result.path("status").asText());
            lastOperationEvidence=evidence;
            if(List.of("Activate","Rollback").contains(action)) {awaitPreparedWorker(evidence,stream,previousStartupRuns,release,producerBaseline);}
            if(action.equals("Checkpoint")) {
                checkpoint=evidence.resolve("checkpoint.json");var manifest=support.json.readTree(Files.readString(checkpoint));
                var quiet=observeFull();assertEquals(manifest.path("snapshotSha256").asText(),quiet.full().get("sha256"),"Completed checkpoint and quiesced observer must use the identical full canonical inventory");
                var reference=Map.<String,Object>of("commandId",commandId,"sourceIdentity",runId+":"+project+":"+database,"manifestSha256",ReleaseContractSupport.sha(checkpoint),"path",checkpoint.toString());
                checkpointSnapshots.put(commandId,quiet);checkpointRefs.put(commandId,reference);checkpointPaths.put(commandId,checkpoint);latestCheckpointId=commandId;
            }
            return result;
            }
        }

        private Map<String,Object> producerInventory() throws Exception {
            var jdbc=jdbc();var result=new LinkedHashMap<String,Object>();
            result.put("observedAt",Instant.now());result.put("mode",mode);result.put("runId",runId);result.put("project",project);result.put("registeredHandlerTypes",support.handlerTypes);
            assertTrue(support.handlerTypes.containsAll(List.of("ORDER_CONFIRM_SCAN","FULFILLMENT_BACKFILL","INITIAL_DELIVERY_SCAN","PAYMENT_QUERY","RETURN_RECOVERY_SCAN.v1","EXCHANGE_REFUND_RECOVERY_SCAN.v1","DASHBOARD_REFRESH.v1")),"Readiness must cover every declared startup/due producer handler");
            result.put("entrypoints",Map.of("applicationRunners",List.of("ConfirmationJobs.confirmationScanStartup","MockShipmentJobs.shipmentBackfillStartup"),"applicationReady",List.of("ReturnRecoveryScanner.startup","ExchangeRefundRecoveryScanner.startup"),"workerPollMs",250,"dashboardCron","0 */5 * * * * Asia/Seoul","apiMediaCleanup",Map.of("initialDelayMs",3600000,"fixedDelayMs",3600000,"retiredHours",24,"workerOwnsMedia",false)));
            result.put("fixedConfirmationRuns",jdbc.queryForList("SELECT run_id,upper_id::text,started_at::text FROM marketplace.order_confirmation_scan ORDER BY run_id",Map.of()));
            result.put("orderCount",jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order",Map.of(),Integer.class));
            result.put("fixedStartupJobs",jdbc.queryForList("SELECT id::text,type,dedupe_key,cause_key,payload,status,attempts,scheduled_at::text,next_run_at::text FROM marketplace.job WHERE type IN ('ORDER_CONFIRM_SCAN','FULFILLMENT_BACKFILL','INITIAL_DELIVERY_SCAN') ORDER BY id",Map.of()));
            result.put("handledDueJobs",jdbc.queryForList("SELECT id::text,type,status,attempts,scheduled_at::text,next_run_at::text FROM marketplace.job WHERE type IN (:types) AND ((status='READY' AND scheduled_at<=clock_timestamp() AND next_run_at<=clock_timestamp()) OR status='RUNNING') ORDER BY id",Map.of("types",support.handlerTypes)));
            result.put("confirmationEligibility",jdbc.queryForList("SELECT u.item_id::text,u.ordinal,u.state AS unit_state,o.state AS order_state,p.status AS policy_status,EXISTS(SELECT 1 FROM marketplace.order_initial_delivery_unit d WHERE d.item_id=u.item_id AND d.ordinal=u.ordinal) AS initial_delivery,EXISTS(SELECT 1 FROM marketplace.order_physical_current c JOIN marketplace.order_physical_source s ON s.id=c.physical_id WHERE c.item_id=u.item_id AND c.ordinal=u.ordinal AND s.round>0) AS physical_round,EXISTS(SELECT 1 FROM marketplace.order_physical_current c JOIN marketplace.order_physical_source s ON s.id=c.physical_id JOIN marketplace.order_physical_delivery d ON d.physical_id=s.id WHERE c.item_id=u.item_id AND c.ordinal=u.ordinal AND s.round>0) AS physical_delivery FROM marketplace.order_unit_lifecycle u JOIN marketplace.purchase_order o ON o.id=u.order_id JOIN marketplace.order_aftercare_policy p ON p.order_id=u.order_id ORDER BY u.item_id,u.ordinal",Map.of()));
            result.put("eligibleRetiredMedia",jdbc.queryForObject("SELECT count(*) FROM marketplace.media_asset WHERE retired_at<clock_timestamp()-interval '24 hours'",Map.of(),Integer.class));
            var files=new ArrayList<Map<String,Object>>();try(var paths=Files.walk(media)) {for(var file:paths.filter(Files::isRegularFile).sorted().toList()) {files.add(Map.of("path",media.relativize(file).toString(),"modifiedAt",Files.getLastModifiedTime(file).toInstant(),"size",Files.size(file)));}}
            result.put("mediaFileAgeEvidence",files);return result;
        }

        private void awaitPreparedWorker(Path evidence,HttpExchange stream,Map<String,java.util.Set<String>> previousStartupRuns,String expectedRelease,Map<String,Object> producerBaseline) throws Exception {
            String id=UUID.randomUUID().toString(),name="pre-existing worker queue readiness";
            Instant started=Instant.now();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            emit(stream,Map.of("event","start","id",id,"name",name,"limitMs",60000));
            var observations=new ArrayList<Map<String,Object>>();var expectedFailed=new java.util.HashSet<String>();
            var recorded=new java.util.HashSet<String>();boolean passed=false;Map<String,Object> producerAfter=Map.of();
            try {
                var activeState=support.json.readTree(Files.readString(evidence.resolve("result.json"))).path("result");
                var observedWorker=verifiedWorker(activeState,evidence,expectedRelease);
                while(true) {
                    var startupRows=new TreeMap<String,List<Map<String,Object>>>();
                    for(String family:ReleaseStartupPreparation.TYPES.keySet()) {
                        startupRows.put(family,jdbc().queryForList("SELECT r.id::text AS run_id,s.stage,s.completed,s.first_job_id::text,j.type,j.status,j.cause_key FROM marketplace.claim_"+family+"_recovery_run r JOIN marketplace.claim_"+family+"_recovery_stage s ON s.run_id=r.id JOIN marketplace.job j ON j.id=s.first_job_id",Map.of()));
                    }
                    var startup=ReleaseStartupPreparation.observe(previousStartupRuns,startupRows);
                    var rows=jdbc().queryForList("SELECT id::text,type,status,attempts,scheduled_at::text,next_run_at::text,last_error,((status='READY' AND scheduled_at<=clock_timestamp() AND next_run_at<=clock_timestamp()) OR status='RUNNING') AS due FROM marketplace.job ORDER BY next_run_at,id",Map.of());
                    if(observations.isEmpty()) {rows.stream().filter(row->row.get("status").equals("FAILED")).forEach(row->expectedFailed.add(row.get("id").toString()));}
                    int due=0;var categories=new LinkedHashMap<String,List<String>>();
                    for(String category:List.of("completed","expectedTerminal","future","unsupported","due")) {categories.put(category,new ArrayList<>());}
                    for(var row:rows) {
                        String job=row.get("id").toString(),status=row.get("status").toString(),category;
                        if(recorded.add(job)) {observations.add(Map.of("event","cohort-entry","observed",Instant.now().toString(),"job",new LinkedHashMap<>(row)));}
                        if(status.equals("FAILED")) {assertTrue(expectedFailed.contains(job),"Preparation worker produced an unexpected terminal job: "+job);category="expectedTerminal";}
                        else if(status.equals("SUCCEEDED")) {category="completed";}
                        else if(!support.handlerTypes.contains(row.get("type").toString())) {category="unsupported";}
                        else if(Boolean.TRUE.equals(row.get("due"))) {category="due";due++;}
                        else {category="future";}
                        categories.get(category).add(job);
                    }
                    observations.add(Map.of("event","observation","observed",Instant.now().toString(),"categories",categories,"startup",startup.families()));
                    assertTrue(System.nanoTime()<deadline,"Pre-existing handled jobs did not drain within finite preparation");
                    if(startup.complete()&&due==0) {
                        producerAfter=producerInventory();assertTrue(System.nanoTime()<deadline,"Producer inventory must finish inside existing preparation deadline");
                        workerOrigin.activated(observedWorker);passed=true;break;
                    }
                    Thread.sleep(100);
                }
            } finally {
                Files.writeString(evidence.resolve("producer-inventory.json"),support.json.writeValueAsString(Map.of("beforeActivation",producerBaseline,"afterPreparation",producerAfter,"preparationPassed",passed)));
                Files.writeString(evidence.resolve("worker-preparation.json"),support.json.writeValueAsString(Map.of("started",started,"ended",Instant.now(),"deadline",started.plusSeconds(60),"limitMs",60000,"registeredHandlerTypes",support.handlerTypes,"previousStartupRuns",previousStartupRuns,"status",passed?"passed":"failed","observations",observations)));
                emit(stream,Map.of("event","end","id",id,"name",name,"status",passed?"passed":"failed"));
            }
        }

        void observer() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(operationLifecycle.executor());
            server.createContext("/",exchange->{
                try(var requestScope=operationLifecycle.enterRequest()) {
                boolean streaming=false;String observedAction="read-only-observation";
                try {
                    if(!observerKey.equals(exchange.getRequestHeaders().getFirst("X-Observer-Key"))) {response(exchange,403,Map.of("error","OBSERVER_KEY_REQUIRED"));return;}
                    String path=exchange.getRequestURI().getPath();
                    if(path.equals("/operation")&&exchange.getRequestMethod().equals("POST")) {
                        var body=support.json.readTree(exchange.getRequestBody().readAllBytes());String action=body.path("action").asText();
                        observedAction=action;
                        assertTrue(List.of("Checkpoint","Activate","Rollback","Restore").contains(action));
                        exchange.getResponseHeaders().set("Content-Type","application/x-ndjson");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(200,0);
                        streaming=true;
                        String release=action.equals("Activate")?"R1":action.equals("Rollback")?"R0":active;
                        var result=new LinkedHashMap<String,Object>();result.put("ready",true);result.put("releaseId",release);result.put("action",action);result.put("mode",mode);result.put("sourceProject",project);
                        if(action.equals("Restore")) {
                            String selected=body.path("checkpointRef").path("commandId").asText();
                            assertTrue(checkpointRefs.containsKey(selected),"Explicit completed checkpoint reference required");assertEquals(support.json.readTree(support.json.writeValueAsString(checkpointRefs.get(selected))),body.path("checkpointRef"));
                            var stopped=snapshots.get(body.path("beforeSnapshotId").asText());assertTrue(stopped!=null&&checkpointSnapshots.get(latestCheckpointId).id().equals(stopped.id()),"Latest separate source checkpoint must stop the source before restore");
                            var sourceBefore=observeFull();ReleaseSnapshotContract.exact(stopped,sourceBefore);
                            Stack restore=stacks.stream().filter(s->s.source==this).findFirst().orElseThrow();
                            restore.operation("Restore",active,"NONE",checkpointPaths.get(selected),exchange);Path restoredEvidence=restore.lastOperationEvidence;
                            System.out.println("D2_CREATED_DATABASE="+restore.database);ReleaseCredentialPreparation.assertInvalidated(restore.jdbc());
                            Instant started=Instant.now();restore.operation("Activate",active,"NONE",null,exchange);restore.active=active;
                            var live=dataPhase(exchange,"verify selected checkpoint and scheduled producer graph",()->{var reopened=restore.observeSettledFull(checkpointSnapshots.get(selected));return restore.liveProof(checkpointSnapshots.get(selected),reopened,started,true,snapshotWorkers.get(checkpointSnapshots.get(selected).id()));});
                            var sourceAfter=observeFull();ReleaseSnapshotContract.exact(stopped,sourceAfter);
                            var original=support.json.readTree(Files.readString(checkpointPaths.get(selected)));var beforeInvalidation=support.json.readTree(Files.readString(restoredEvidence.resolve("before-invalidation.json")));
                            assertEquals(original.path("snapshotSha256").asText(),beforeInvalidation.path("snapshotSha256").asText());assertEquals(checkpointRefs.get(selected).get("manifestSha256"),beforeInvalidation.path("checkpointManifestSha256").asText());
                            var boundary=Map.of("passed",true,"beforeDigest",original.path("snapshotSha256").asText(),"afterDigest",beforeInvalidation.path("snapshotSha256").asText(),"tableInventory",checkpointSnapshots.get(selected).full().get("tableInventory"),"sequencesEqual",true,"mediaEqual",true);
                            var sourceProof=Map.of("passed",true,"beforeSnapshotId",stopped.id(),"afterSnapshotId",sourceAfter.id(),"beforeDigest",stopped.full().get("sha256"),"afterDigest",sourceAfter.full().get("sha256"));
                            result.put("checkpointRef",checkpointRefs.get(selected));result.put("preservation",Map.of("boundaryExact",boundary,"live",live,"sourceStopped",sourceProof));
                        } else {
                            var before=action.equals("Rollback")?snapshots.get(body.path("beforeSnapshotId").asText()):null;
                            if(action.equals("Rollback")) {assertTrue(before!=null,"Explicit pre-rollback snapshot required");}
                            Instant started=Instant.now();operation(action,release,active,null,exchange);if(!action.equals("Checkpoint")) {active=release;}
                            if(action.equals("Checkpoint")) {result.put("checkpointRef",checkpointRefs.get(latestCheckpointId));var quiet=checkpointSnapshots.get(latestCheckpointId);result.put("quiescedSnapshot",Map.of("snapshotId",quiet.id(),"full",quiet.full()));}
                            if(action.equals("Rollback")) {
                                var proof=support.json.readTree(Files.readString(lastOperationEvidence.resolve("rollback-preservation.json")));assertEquals("passed",proof.path("status").asText());assertTrue(proof.path("equal").path("all").asBoolean());
                                var preservation=dataPhase(exchange,"verify operator quiet baseline and scheduled producer graph",()->{
                                var quiet=ReleaseQuietSnapshot.load(support,proof,before);snapshots.put(quiet.id(),quiet);
                                var dynamic=new java.util.HashSet<>(ReleaseSnapshotContract.AUDIT);dynamic.addAll(ReleaseScheduledWork.TABLES);dynamic.addAll(List.of("job","outbox_event","spring_session"));
                                var protectedTables=new ArrayList<String>();for(String table:ReleaseSnapshotContract.coverage()) {if(!dynamic.contains(table)) {assertTrue(ReleaseSnapshotContract.index(before,table).equals(ReleaseSnapshotContract.index(quiet,table)),"Original pre-quiesce business rows changed: "+table);protectedTables.add(table);}}
                                var after=observeSettledFull(quiet);var live=new LinkedHashMap<>(liveProof(quiet,after,started,false,snapshotWorkers.get(before.id())));live.put("inputSnapshotId",before.id());live.put("quietBaselineDigest",quiet.full().get("sha256"));live.put("protectedInputTables",protectedTables);
                                var boundary=new LinkedHashMap<String,Object>();boundary.put("passed",true);boundary.put("beforeSnapshotId",quiet.id());boundary.put("beforeDigest",proof.path("beforeSha256").asText());boundary.put("afterDigest",proof.path("afterSha256").asText());boundary.put("tableInventory",after.full().get("tableInventory"));boundary.put("sequencesEqual",proof.path("equal").path("sequences").asBoolean());boundary.put("mediaEqual",proof.path("equal").path("media").asBoolean());
                                return Map.of("boundaryExact",boundary,"live",live);});result.put("preservation",preservation);
                            }
                        }
                        Files.writeString(support.folder.resolve("observer-operation-"+UUID.randomUUID()+".json"),support.json.writeValueAsString(result));
                        emit(exchange,Map.of("event","result","result",result));exchange.close();return;
                    }
                    if(!exchange.getRequestMethod().equals("GET")) {response(exchange,405,Map.of("error","READ_ONLY"));return;}
                    if(path.equals("/snapshot")) {response(exchange,200,snapshot());return;}
                    if(path.equals("/notification-retry")) {
                        String query=exchange.getRequestURI().getRawQuery(),id=query!=null&&query.startsWith("failedJob=")?query.substring(10):"";
                        assertTrue(id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
                        var rows=jdbc().queryForList("SELECT r.id::text,r.previous_job::text,r.job_id::text,r.event_id::text,r.operator_id::text,j.status,(SELECT count(*) FROM marketplace.notification_inbox n WHERE n.event_id=r.event_id) AS inbox_rows FROM marketplace.notification_retry r JOIN marketplace.job j ON j.id=r.job_id WHERE r.previous_job=CAST(:id AS uuid)",Map.of("id",id));
                        response(exchange,200,Map.of("auditRows",rows.size(),"rows",rows));return;
                    }
                    if(path.equals("/purchase")) {String query=exchange.getRequestURI().getRawQuery();String number=query!=null&&query.startsWith("orderNumber=")?query.substring(12):"";assertTrue(number.matches("O[0-9a-f]{32}"));response(exchange,200,purchase(number));return;}
                    response(exchange,404,Map.of("error","NOT_FOUND"));
                } catch(Throwable failure) {
                    reportObserverFailure(support.folder,exchange,failure,observedAction,streaming);
                }
                }
            });server.start();observers.add(server);
        }

        Map<String,Object> snapshot() {
            var jdbc=jdbc();ReleaseSnapshotContract.Snapshot observed;
            try {observed=observeFull();}catch(Exception failure) {throw new IllegalStateException("Full snapshot observation failed",failure);}
            var business=new java.util.TreeMap<>(observed.tables());business.keySet().removeIf(table->table.startsWith("spring_session")||table.startsWith("guest_")&&!List.of("guest_buyer","guest_order_reference").contains(table)||List.of("email_verification_token","password_reset_token","job","outbox_event","flyway_schema_history").contains(table)||table.startsWith("dashboard_")||table.endsWith("_limit")||table.startsWith("notification_")||table.startsWith("phone_")||table.equals("media_namespace"));
            var hashes=new java.util.TreeMap<String,Object>();
            try {
                for(var table:business.entrySet()) {
                    var rows=table.getValue();var rowHashes=new ArrayList<String>();
                    for(var row:rows) {rowHashes.add(digest(support.json.writeValueAsBytes(row)));}
                    rowHashes.sort(String::compareTo);hashes.put(table.getKey(),Map.of("rows",rows.size(),"rowSha256",rowHashes,"sha256",ReleaseSnapshotContract.hash(rows)));
                }
            } catch(Exception failure) {throw new IllegalStateException("Canonical business hashing failed",failure);}
            return Map.of("snapshotId",observed.id(),"full",observed.full(),"business",hashes,"guest",Map.of("verifications",jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_attempt",Map.of(),Integer.class),"checkouts",jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_checkout",Map.of(),Integer.class),"accessSessions",jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_access_session",Map.of(),Integer.class),"providerCalls",providerBoundaryCalls()));
        }
        ReleaseSnapshotContract.Snapshot observeFull() throws Exception {
            var snapshot=ReleaseSnapshotContract.capture(support,jdbc(),media);snapshots.put(snapshot.id(),snapshot);
            var origin=workerOrigin.observeData();if(origin.worker()!=null) {snapshotWorkers.put(snapshot.id(),origin.worker());}
            var safe=new LinkedHashMap<>(ReleaseSnapshotContract.safe(snapshot));safe.put("workerOrigin",origin);
            Files.writeString(support.folder.resolve("snapshot-"+snapshot.id()+".json"),support.json.writeValueAsString(safe));
            return snapshot;
        }
        ReleaseSnapshotContract.Snapshot observeSettledFull(ReleaseSnapshotContract.Snapshot baseline) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);var samples=new ArrayList<String>();
            while(true) {
                var snapshot=observeFull();samples.add(snapshot.id());boolean complete=true;
                for(var run:snapshot.tables().get("dashboard_run")) {if(run.path("state").asText().equals("PENDING")) {var job=ReleaseSnapshotContract.by(snapshot,"job","id",run.path("job_id").asText());if(List.of("READY","RUNNING").contains(job.path("status").asText())) {complete=false;}}}
                var old=baseline.sequences().stream().filter(row->row.get("name").equals("dashboard_run_id_seq")).findFirst().orElse(null);
                var current=snapshot.sequences().stream().filter(row->row.get("name").equals("dashboard_run_id_seq")).findFirst().orElse(null);
                if(current!=null&&!current.equals(old)) {long max=snapshot.tables().get("dashboard_run").stream().mapToLong(row->row.path("id").asLong()).max().orElse(0);if(current.get("value")==null||!current.get("value").toString().equals(Long.toString(max))) {complete=false;}}
                assertTrue(System.nanoTime()<deadline,"Scheduled producer commit/sequence did not become observable within its finite observation deadline");
                if(complete) {Files.writeString(support.folder.resolve("scheduled-observation-"+UUID.randomUUID()+".json"),support.json.writeValueAsString(Map.of("deadlineSeconds",10,"samples",samples,"finalSnapshotId",snapshot.id())));return snapshot;}
                Thread.sleep(50);
            }
        }
        ReleaseWorkerOrigin.Incarnation verifiedWorker(JsonNode state,Path evidence,String expectedRelease) throws Exception {
            JsonNode observed=support.json.readTree("{}");Path observation=evidence.resolve("result.json");
            if(mode.equals("P")) {
                String worker="";for(var artifact:state.path("artifactIdentities")) {if(artifact.path("service").asText().equals("worker")) {assertTrue(worker.isEmpty());worker=artifact.path("id").asText();}}
                assertTrue(worker.matches("[0-9a-f]{64}"),"Actual full worker container identity is required");
                observation=evidence.resolve("verified-worker-incarnation.json");
                observed=ReleaseWorkerObservation.observe(worker,evidence,support.root,operationLifecycle);
            }
            return ReleaseWorkerOrigin.verify(state,observed,mode,runId,project,expectedRelease,evidence.toString(),ReleaseContractSupport.sha(evidence.resolve("result.json")),ReleaseContractSupport.sha(observation));
        }
        Map<String,Object> liveProof(ReleaseSnapshotContract.Snapshot before,ReleaseSnapshotContract.Snapshot after,Instant started,boolean restored,ReleaseWorkerOrigin.Incarnation previousWorker) throws Exception {
            var state=support.json.readTree(Files.readString(lastOperationEvidence.resolve("result.json"))).path("result");var currentWorker=workerOrigin.snapshot();
            ReleaseWorkerOrigin.distinct(previousWorker,currentWorker,restored?source.runId:runId,restored?source.project:project);assertEquals(active,state.path("activeReleaseId").asText());assertEquals(active,currentWorker.releaseId());
            if(mode.equals("L")) {var worker=state.path("hostRoles").path("worker");assertTrue(worker.path("running").asBoolean());assertEquals(runId,worker.path("runId").asText());assertEquals(state.path("activeReleaseId").asText(),worker.path("releaseId").asText());}
            var proof=new LinkedHashMap<>(ReleaseSnapshotContract.validate(before,after,started,Instant.now(),restored));
            proof.put("workerOrigin",Map.of("previousIdentity",previousWorker.identity(),"actualIdentity",currentWorker.identity(),"previousBinding",previousWorker,"actualBinding",currentWorker,"commandEvidence",lastOperationEvidence.toString(),"commandEvidenceSha256",ReleaseContractSupport.sha(lastOperationEvidence.resolve("result.json")),"releaseId",state.path("activeReleaseId").asText()));
            return proof;
        }
        int providerBoundaryCalls() {
            if(mode.equals("L")) {return -1;}
            try {
                Path file=support.folder.getParent().resolve("public-demo-runtime/provider-boundary-proof.json");
                var proof=support.json.readTree(Files.readString(file));assertTrue(proof.path("businessUnchanged").asBoolean());
                try(var jar=new java.util.jar.JarFile(r1jar.toFile())) {
                    for(var entry:proof.path("implementationHashes").properties()) {
                        var item=jar.getJarEntry("BOOT-INF/classes"+entry.getKey());assertTrue(item!=null);
                        try(var bytes=jar.getInputStream(item)) {assertEquals(entry.getValue().asText(),java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())));}
                    }
                }
                int calls=proof.path("phoneProviderBeans").asInt(-1)+proof.path("paymentProviderCalls").asInt(-1)+proof.path("refundProviderCalls").asInt(-1);
                assertEquals(0,calls,"Same-source public-demo-runtime consumer proof must show no configured phone provider and no provider calls");
                Files.writeString(support.folder.resolve("public-provider-consumer.json"),support.json.writeValueAsString(Map.of("scope","same-implementation registered public-demo-runtime consumer; not a Compose process counter","proof",file.toString(),"sha256",ReleaseContractSupport.sha(file),"implementationHashes",proof.path("implementationHashes"),"calls",calls)));
                return calls;
            } catch(Exception failure) {throw new IllegalStateException("Registered public-demo-runtime provider evidence required before public release browser",failure);}
        }
        Map<String,Object> purchase(String number) {
            var jdbc=jdbc();var p=Map.of("number",number);var result=new LinkedHashMap<String,Object>();
            result.put("order",jdbc.queryForMap("SELECT id::text,order_number,state,amount,mode FROM marketplace.purchase_order WHERE order_number=:number",p));
            result.put("inventory",jdbc.queryForList("SELECT r.status FROM marketplace.inventory_reservation r JOIN marketplace.order_reservation b ON b.reservation_id=r.id JOIN marketplace.purchase_order o ON o.id=b.order_id WHERE o.order_number=:number",p));
            result.put("attempts",jdbc.queryForList("SELECT a.state,a.id::text,m.approval_effects,(SELECT count(*) FROM marketplace.payment_event e WHERE e.attempt_id=a.id AND e.action='SUCCEEDED') success_events FROM marketplace.payment_attempt a LEFT JOIN marketplace.payment_mock m ON m.attempt_id=a.id JOIN marketplace.purchase_order o ON o.id=a.order_id WHERE o.order_number=:number",p));
            result.put("shipments",jdbc.queryForList("SELECT s.id::text,s.order_id::text,s.order_number,s.state FROM marketplace.shipment s JOIN marketplace.purchase_order o ON o.id=s.order_id WHERE o.order_number=:number",p));
            result.put("shipmentConsumers",jdbc.queryForList("SELECT s.id::text shipment_id,s.order_id::text,s.order_number,j.id::text job_id,j.type job_type,j.cause_key,j.dedupe_key,j.status,j.attempts job_attempts,f.job_id::text claim_job_id,f.attempt claim_attempt FROM marketplace.shipment s JOIN marketplace.purchase_order o ON o.id=s.order_id LEFT JOIN marketplace.job j ON j.type='SHIPMENT_CREATED' AND j.cause_key='created:'||s.id::text AND j.dedupe_key='created:'||s.id::text LEFT JOIN marketplace.job_claim_fact f ON f.job_id=j.id AND f.attempt=j.attempts WHERE o.order_number=:number ORDER BY s.id",p));return result;
        }
    }

    static void reportObserverFailure(Path folder,HttpExchange exchange,Throwable failure,String action,boolean streaming) throws java.io.IOException {
        String failureId=UUID.randomUUID().toString();var trace=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(trace));
        try {
            ReleaseSnapshotContract.privateFile(folder.resolve("observer-failed-"+failureId+".private.txt"),trace.toString());
            var safe=Map.of("id",failureId,"action",action,"type",failure.getClass().getName(),"messageSha256",ReleaseSnapshotContract.hash(failure.getMessage()),"stack",java.util.Arrays.stream(failure.getStackTrace()).map(StackTraceElement::toString).toList(),"suppressedCount",failure.getSuppressed().length,"originalThrowablePrivate",true);
            Files.writeString(folder.resolve("observer-failed-"+failureId+".json"),ReleaseSnapshotContract.JSON.writeValueAsString(safe));
            if(streaming) {
                String phase="observer "+action+" verification: "+failure.getClass().getSimpleName()+" ("+failureId+")";
                for(var event:List.of(Map.of("event","start","id",failureId,"name",phase,"limitMs",10000),Map.of("event","end","id",failureId,"name",phase,"status","failed"))) {exchange.getResponseBody().write((ReleaseSnapshotContract.JSON.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();}
            } else {
                byte[] bytes=ReleaseSnapshotContract.JSON.writeValueAsBytes(Map.of("error","OBSERVER_VERIFICATION_FAILED","failureId",failureId,"type",failure.getClass().getName()));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(500,bytes.length);exchange.getResponseBody().write(bytes);
            }
        } catch(Exception reporting) {failure.addSuppressed(reporting);Files.writeString(folder.resolve("observer-reporting-failed-"+failureId+".json"),ReleaseSnapshotContract.JSON.writeValueAsString(Map.of("originalType",failure.getClass().getName(),"reportingType",reporting.getClass().getName())));}
        finally {exchange.close();}
    }

    private <T> T dataPhase(HttpExchange exchange,String name,java.util.concurrent.Callable<T> observation) throws Exception {
        String id=UUID.randomUUID().toString();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);emit(exchange,Map.of("event","start","id",id,"name",name,"limitMs",60000));
        try {T result=observation.call();assertTrue(System.nanoTime()<deadline,"Finite data observation phase expired");emit(exchange,Map.of("event","end","id",id,"name",name,"status","passed"));return result;}
        catch(Exception|Error failure) {try {emit(exchange,Map.of("event","end","id",id,"name",name,"status","failed"));}catch(Exception reporting) {failure.addSuppressed(reporting);}throw failure;}
    }

    private void emit(HttpExchange exchange,Object value) throws Exception {if(exchange!=null) {exchange.getResponseBody().write((support.json.writeValueAsString(value)+"\n").getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();}}
    private void response(HttpExchange exchange,int status,Object value) throws Exception {byte[] bytes=support.json.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
    private String digest(byte[] bytes) throws Exception {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
}
