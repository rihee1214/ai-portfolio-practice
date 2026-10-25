package com.example.marketplace.media.infrastructure;

import com.example.marketplace.media.api.MediaException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Single database, single process root. All callers synchronize on this instance. */
public final class LocalImages implements AutoCloseable {
    private final Path root;
    private final FileChannel channel;
    private final FileLock lock;
    final java.util.Set<UUID> inFlight=new java.util.HashSet<>();
    public LocalImages(Path configured,UUID namespace) throws IOException {
        root=configured.toAbsolutePath().normalize();
        for(Path p=root;p!=null;p=p.getParent()) { if(Files.isSymbolicLink(p)) { throw new IOException("Linked media path rejected"); } }
        Files.createDirectories(root);
        if(!root.toRealPath().equals(root)) { throw new IOException("Redirected media root rejected"); }
        Path lockPath=root.resolve(".lock"), marker=root.resolve(".database");
        if(Files.isSymbolicLink(lockPath)||Files.isSymbolicLink(marker)) { throw new IOException("Linked metadata rejected"); }
        channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock acquired=null;
        try {
            acquired=channel.tryLock(); if(acquired==null) { throw new IOException("Media root already in use"); }
            if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS)) {
                if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.size(marker)>100||!Files.readString(marker).equals(namespace.toString())) { throw new IOException("Media database mismatch"); }
            } else {
                try(var entries=Files.list(root)) { if(entries.anyMatch(path->!path.equals(lockPath))) { throw new IOException("Unmanaged media root rejected"); } }
                Path temporary=root.resolve(".database-"+UUID.randomUUID()+".tmp");
                Files.writeString(temporary,namespace.toString(),StandardOpenOption.CREATE_NEW);
                Files.move(temporary,marker,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            lock=acquired;
        } catch(IOException|RuntimeException failure) {
            if(acquired!=null) { acquired.release(); } channel.close(); throw failure;
        }
    }
    Path path(UUID id) { return root.resolve(id+".png"); }
    void write(UUID id,byte[] bytes) throws IOException { Files.write(path(id),bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE); }
    byte[] read(UUID id) throws IOException {
        Path file=path(id);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>ImageNormalizer.MAX_BYTES) { throw new MediaException(404); }
        try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] data=in.readNBytes(ImageNormalizer.MAX_BYTES+1); if(data.length>ImageNormalizer.MAX_BYTES) { throw new MediaException(404); } return data;
        }
    }
    boolean remove(UUID id) throws IOException {
        Path file=path(id);
        if(Files.isSymbolicLink(file)||Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Non-regular media file rejected"); }
        return Files.deleteIfExists(file);
    }
    java.util.List<UUID> oldFiles(java.time.Instant cutoff) throws IOException {
        try(var entries=Files.list(root)) {
            var ids=new java.util.ArrayList<UUID>();
            for(Path path:entries.toList()) {
                String name=path.getFileName().toString();
                if(name.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.png")&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                        &&Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) { ids.add(UUID.fromString(name.substring(0,36))); }
            }
            return ids;
        }
    }
    @Override public void close() throws IOException { lock.release(); channel.close(); }
}
