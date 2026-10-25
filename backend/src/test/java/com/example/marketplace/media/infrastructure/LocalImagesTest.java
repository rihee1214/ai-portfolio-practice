package com.example.marketplace.media.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalImagesTest {
    @TempDir Path directory;
    @Test void namespaceAndExclusiveProcessLockProtectExistingFiles() throws Exception {
        UUID namespace=UUID.randomUUID(),id=UUID.randomUUID(); Path root=directory.resolve("root");
        try(var files=new LocalImages(root,namespace)) {
            files.write(id,new byte[]{1,2,3}); assertArrayEquals(new byte[]{1,2,3},files.read(id));
            assertThrows(Exception.class,()->new LocalImages(root,namespace));
            assertThrows(java.nio.file.FileAlreadyExistsException.class,()->files.write(id,new byte[]{4}));
        }
        assertThrows(java.io.IOException.class,()->new LocalImages(root,UUID.randomUUID()));
        try(var files=new LocalImages(root,namespace)) { assertArrayEquals(new byte[]{1,2,3},files.read(id)); }
    }
    @Test void unmanagedRootAndNonRegularAssetAreNeverDeleted() throws Exception {
        Path unmanaged=Files.createDirectory(directory.resolve("unmanaged")); Files.writeString(unmanaged.resolve("keep.txt"),"keep");
        assertThrows(java.io.IOException.class,()->new LocalImages(unmanaged,UUID.randomUUID())); assertTrue(Files.exists(unmanaged.resolve("keep.txt")));
        try(var files=new LocalImages(directory.resolve("valid"),UUID.randomUUID())) {
            UUID id=UUID.randomUUID(); Files.createDirectory(files.path(id));
            assertThrows(java.io.IOException.class,()->files.remove(id)); assertTrue(Files.isDirectory(files.path(id)));
            assertFalse(files.remove(UUID.randomUUID()));
        }
    }
}
