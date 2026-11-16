package com.example.marketplace.dbtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Deliberately wrong DB/media pairing with otherwise valid immutable manifest integrity. */
final class ReleaseMixedCheckpoint {
    static final String RUNNING_SERVICES_FORMAT="{{.Label `com.docker.compose.service`}}";
    static Map<String,Object> create(Map<String,Object> databaseCheckpoint,Map<String,Object> mediaCheckpoint) {
        var originalMedia=(Map<?,?>)databaseCheckpoint.get("media");var foreignMedia=(Map<?,?>)mediaCheckpoint.get("media");
        assertNotEquals(originalMedia.get("namespace"),foreignMedia.get("namespace"),"Fault fixture requires genuinely different namespaces");
        var result=new LinkedHashMap<>(databaseCheckpoint);result.put("media",mediaCheckpoint.get("media"));
        result.put("snapshotSha256",digest(result));return result;
    }
    static String digest(Map<String,Object> checkpoint) {
        var media=(Map<?,?>)checkpoint.get("media");
        return ReleaseSnapshotContract.hash(Map.of("database",checkpoint.get("database"),"media",Map.of("namespace",media.get("namespace"),"files",media.get("files"),"referenced",media.get("referenced"),"unreferenced",media.get("unreferenced"))));
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if(args.length!=3) {throw new IllegalArgumentException("Two retained checkpoints and a fresh fixture path required");}
        var json=ReleaseSnapshotContract.JSON;Path output=Path.of(args[2]);assertFalse(Files.exists(output));
        var mixed=create(json.readValue(Files.readString(Path.of(args[0])),Map.class),json.readValue(Files.readString(Path.of(args[1])),Map.class));
        Files.createFile(output);Files.writeString(output,json.writeValueAsString(mixed));
    }
}
