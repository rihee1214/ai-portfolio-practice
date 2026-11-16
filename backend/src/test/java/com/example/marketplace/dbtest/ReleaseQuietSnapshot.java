package com.example.marketplace.dbtest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import static com.example.marketplace.dbtest.ReleaseSnapshotContract.JSON;
import static com.example.marketplace.dbtest.ReleaseSnapshotContract.require;

/** Imports the operator's real, private, post-API/pre-worker observation, not a later live sample. */
final class ReleaseQuietSnapshot {
    static ReleaseSnapshotContract.Snapshot load(ReleaseContractSupport support,JsonNode proof,ReleaseSnapshotContract.Snapshot input) throws Exception {
        var side=proof.path("after");var artifact=side.path("privateRows");Path path=Path.of(artifact.path("path").asText()).toAbsolutePath().normalize();
        require(path.startsWith(support.folder.toAbsolutePath().normalize())&&Files.isRegularFile(path)&&!Files.isSymbolicLink(path),"Private quiet artifact outside owned evidence");
        require(Files.size(path)==artifact.path("size").asLong()&&ReleaseContractSupport.sha(path).equals(artifact.path("sha256").asText()),"Private quiet artifact identity mismatch");
        require(artifact.path("access").path("protected").asBoolean()&&artifact.path("access").path("ownerOnly").asBoolean()&&artifact.path("access").path("emptyBeforeWrite").asBoolean(),"Private quiet output was not protected before writing");
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class);require(acl!=null,"Private quiet artifact ACL unavailable");var owner=acl.getOwner();require(acl.getAcl().size()==1&&acl.getAcl().getFirst().principal().equals(owner),"Private quiet artifact has non-owner access");
        var tables=new TreeMap<String,List<JsonNode>>();var inventory=new TreeMap<String,Object>();
        for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)) {
            if(line.isBlank()) {continue;}var row=JSON.readTree(line);String table=row.path("table").asText();require(ReleaseSnapshotContract.coverage().contains(table)&&!tables.containsKey(table),"Unregistered or duplicate quiet table");
            var canonical=row.path("canonicalRows");var typed=row.path("rows");var hashes=row.path("rowHashes");int count=row.path("count").asInt();
            require(canonical.size()==count&&typed.size()==count&&hashes.size()==count,"Quiet row inventory incomplete");var values=new ArrayList<JsonNode>();var original=new ArrayList<String>();
            for(int index=0;index<count;index++) {
                String text=canonical.get(index).asText();original.add(text);var parsed=JSON.readTree(text);
                require(parsed.equals(typed.get(index)),"Precision-preserving canonical/typed quiet row mismatch");
                require(digest("SHA-256",text).equals(hashes.get(index).asText()),"Quiet canonical row SHA mismatch");values.add(parsed);
            }
            require(digest("MD5",String.join("\n",original)).equals(row.path("md5").asText()),"Quiet canonical row multiset MD5 mismatch");
            tables.put(table,values);inventory.put(table,JSON.readValue(JSON.writeValueAsString(Map.of("count",count,"md5",row.path("md5").asText(),"rowHashes",hashes)),Map.class));
        }
        require(tables.keySet().equals(ReleaseSnapshotContract.coverage()),"Quiet all-table coverage incomplete");
        var sequences=new ArrayList<Map<String,Object>>();for(var sequence:side.path("database").path("__sequences")) {sequences.add(JSON.readValue(sequence.toString(),Map.class));}inventory.put("__sequences",sequences);
        require(JSON.readTree(JSON.writeValueAsString(inventory)).equals(side.path("database")),"Private quiet rows not bound to full operator inventory");
        var media=JSON.readValue(side.path("media").toString(),Map.class);var files=new TreeMap<String,String>();for(var file:side.path("media").path("files")) {files.put(file.path("path").asText(),file.path("sha256").asText());}
        var observation=new LinkedHashMap<String,Object>();observation.put("sequenceDiagnostics",JSON.readValue(side.path("sequenceDiagnostics").toString(),List.class));observation.put("observedAt",side.path("observedAt").asText());observation.put("operatorArtifact",artifact);
        var quiet=new ReleaseSnapshotContract.Snapshot(UUID.randomUUID().toString(),tables,input.keys(),sequences,ReleaseSnapshotContract.hash(files),input.locators(),inventory,media,observation);
        String digest=quiet.full().get("sha256").toString();require(digest.equals(artifact.path("snapshotSha256").asText())&&digest.equals(proof.path("afterSha256").asText())&&digest.equals(proof.path("beforeSha256").asText()),"Private quiet artifact/full boundary digest mismatch");
        Files.writeString(support.folder.resolve("snapshot-"+quiet.id()+".json"),JSON.writeValueAsString(ReleaseSnapshotContract.safe(quiet)));return quiet;
    }
    static String digest(String algorithm,String text) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));}
}
