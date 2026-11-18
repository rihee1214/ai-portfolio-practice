package com.example.marketplace.dbtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A fixed historical application is first and exclusive; only enumerated test support follows. */
final class HistoricalReleaseArtifacts {
    private static final List<String> HELPERS=List.of("HistoricalReleaseProbe","ReleaseReconciliationFixture",
            "SettlementContractFixture","PostgresContractFixture","ParticipantSnapshots","ContractTiming","ReleaseContractSupport","OneJobJdbc");
    private static final List<String> FIXTURES=List.of("AccountRepository","TransferService","BatchService",
            "DedicatedDatabaseTarget","BrowserChildWatchdog");
    private static final List<String> TEST_LIBRARIES=List.of("junit-jupiter-api-","junit-platform-commons-","opentest4j-",
            "apiguardian-api-","assertj-core-","spring-test-","spring-boot-test-","spring-boot-test-autoconfigure-",
            "hamcrest-","awaitility-");
    record Runtime(Path root,Path manifest,String classpath,Path migrations) { }

    static Runtime prepare(Path jar,Path root) throws Exception {
        Files.createDirectory(root);Path classes=Files.createDirectory(root.resolve("classes"));
        Path libraries=Files.createDirectory(root.resolve("lib"));Path helpers=Files.createDirectory(root.resolve("test-support"));
        Path testLibraries=Files.createDirectory(root.resolve("test-lib"));
        var records=new ArrayList<Map<String,Object>>();var entries=new java.util.HashSet<String>();
        try(var archive=new JarFile(jar.toFile())) {
            for(var entry:archive.stream().filter(e -> !e.isDirectory()).sorted(Comparator.comparing(java.util.jar.JarEntry::getName)).toList()) {
                String name=entry.getName();Path destination;
                if(name.startsWith("BOOT-INF/classes/")) {destination=classes.resolve(name.substring("BOOT-INF/classes/".length())).normalize();assertTrue(destination.startsWith(classes));}
                else if(name.startsWith("BOOT-INF/lib/")&&name.endsWith(".jar")) {String leaf=name.substring("BOOT-INF/lib/".length());assertFalse(leaf.contains("/"));destination=libraries.resolve(leaf);}
                else {continue;}
                assertTrue(entries.add(name));Files.createDirectories(destination.getParent());
                byte[] bytes=archive.getInputStream(entry).readAllBytes();Files.write(destination,bytes);
                String sha=sha(bytes);assertEquals(sha,ReleaseContractSupport.sha(destination));
                records.add(Map.of("entry",name,"file",destination.toAbsolutePath().toString(),"sha256",sha));
            }
        }
        assertTrue(Files.isRegularFile(classes.resolve("com/example/marketplace/MarketplaceApplication.class")));
        assertFalse(Files.exists(classes.resolve("db/migration/V130__site_shipping_policy.sql")));
        Path compiled=Path.of(HistoricalReleaseArtifacts.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var helperRecords=new ArrayList<Map<String,Object>>();
        for(String pkg:List.of("dbtest","jdbcfixture")) {
            Path source=compiled.resolve("com/example/marketplace/"+pkg);List<String> allowed=pkg.equals("dbtest")?HELPERS:FIXTURES;
            try(var paths=Files.list(source)) {
                for(var file:paths.filter(Files::isRegularFile).sorted().toList()) {
                    String name=file.getFileName().toString();
                    if(!name.endsWith(".class")||allowed.stream().noneMatch(stem -> name.equals(stem+".class")||name.startsWith(stem+"$"))) {continue;}
                    Path destination=helpers.resolve("com/example/marketplace/"+pkg+"/"+name);Files.createDirectories(destination.getParent());
                    Files.copy(file,destination,StandardCopyOption.COPY_ATTRIBUTES);
                    helperRecords.add(Map.of("source",file.toString(),"file",destination.toString(),"sha256",ReleaseContractSupport.sha(destination)));
                }
            }
        }
        assertTrue(Files.isRegularFile(helpers.resolve("com/example/marketplace/dbtest/HistoricalReleaseProbe.class")));
        Path participantHelper=Path.of("com/example/marketplace/dbtest/ParticipantSnapshots.class");
        assertTrue(Files.isRegularFile(helpers.resolve(participantHelper)),"Current fixture's full-snapshot dependency must be retained");
        assertEquals(ReleaseContractSupport.sha(compiled.resolve(participantHelper)),ReleaseContractSupport.sha(helpers.resolve(participantHelper)),"Retained current test-helper dependency bytes");
        assertFalse(Files.exists(helpers.resolve("com/example/marketplace/MarketplaceApplication.class")));
        var dependencyRecords=new ArrayList<Map<String,Object>>();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String component:classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            Path source=Path.of(component);String name=source.getFileName().toString();
            if(!name.endsWith(".jar")||TEST_LIBRARIES.stream().noneMatch(name::startsWith)) {continue;}
            try(var archive=new JarFile(source.toFile())) {assertFalse(archive.stream().anyMatch(entry -> entry.getName().startsWith("com/example/marketplace/")));}
            Path destination=testLibraries.resolve(name);
            if(!Files.exists(destination)) {Files.copy(source,destination);dependencyRecords.add(Map.of("source",source.toString(),"file",destination.toString(),"sha256",ReleaseContractSupport.sha(destination)));}
        }
        assertTrue(dependencyRecords.stream().anyMatch(record -> Path.of(record.get("file").toString()).getFileName().toString().startsWith("junit-jupiter-api-")));
        var paths=new ArrayList<String>();paths.add(classes.toAbsolutePath().toString());
        try(var files=Files.list(libraries)) {paths.addAll(files.sorted().map(p -> p.toAbsolutePath().toString()).toList());}
        paths.add(helpers.toAbsolutePath().toString());
        try(var files=Files.list(testLibraries)) {paths.addAll(files.sorted().map(p -> p.toAbsolutePath().toString()).toList());}
        var manifest=new LinkedHashMap<String,Object>();manifest.put("historicalCommit","aee626bb8d69ba1b4191d5a4688cb6b39538839c");
        manifest.put("historicalJar",jar.toAbsolutePath().toString());manifest.put("historicalJarSha256",ReleaseContractSupport.sha(jar));
        manifest.put("applicationRoot",classes.toAbsolutePath().toString());manifest.put("libraryRoot",libraries.toAbsolutePath().toString());
        manifest.put("testSupportRoot",helpers.toAbsolutePath().toString());manifest.put("testLibraryRoot",testLibraries.toAbsolutePath().toString());
        manifest.put("historicalEntries",records);manifest.put("testHelpers",helperRecords);manifest.put("testDependencies",dependencyRecords);
        manifest.put("classpath",paths);manifest.put("currentApplicationFallback",false);
        Path output=root.resolve("runtime-manifest.json");Files.writeString(output,new tools.jackson.databind.ObjectMapper().writeValueAsString(manifest));
        return new Runtime(root,output,String.join(java.io.File.pathSeparator,paths),classes.resolve("db/migration"));
    }

    static void run(ReleaseContractSupport support,Runtime runtime,Map<String,Object> request,String label) throws Exception {
        Path requestFile=support.folder.resolve("historical-"+label+".private.json");
        var input=new LinkedHashMap<>(request);input.put("runtimeManifest",runtime.manifest().toString());
        Files.writeString(requestFile,support.json.writeValueAsString(input));
        Path arguments=support.folder.resolve("historical-"+label+".args");
        Files.writeString(arguments,"-cp\n\""+runtime.classpath().replace('\\','/')+"\"\ncom.example.marketplace.dbtest.HistoricalReleaseProbe\n\""+requestFile.toString().replace('\\','/')+"\"\n");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+arguments)
                .redirectErrorStream(true).redirectOutput(support.folder.resolve("historical-"+label+".log").toFile());
        builder.environment().keySet().removeIf(key -> key.equals("CLASSPATH")||key.equals("JAVA_TOOL_OPTIONS")||key.equals("JDK_JAVA_OPTIONS")||key.startsWith("TOSS_")||key.startsWith("FIREBASE_")||key.startsWith("GCLOUD_"));
        var child=builder.start();support.children.add(child);
        com.example.marketplace.jdbcfixture.BrowserChildWatchdog.await(child,support.folder.resolve("historical-"+label+"-progress.ndjson"));
        assertEquals(0,child.exitValue(),"Inspect preserved historical probe "+label+" log");
    }
    static String sha(byte[] bytes) throws Exception {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
}
