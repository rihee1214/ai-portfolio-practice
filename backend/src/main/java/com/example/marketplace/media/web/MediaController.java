package com.example.marketplace.media.web;

import com.example.marketplace.media.api.Media;
import com.example.marketplace.media.api.MediaException;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController @Profile("db")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="marketplace.media.enabled",matchIfMissing=true)
public class MediaController {
    private final Media media;
    public MediaController(Media media) { this.media=media; }
    @GetMapping("/api/media/{kind}/{target}") public ResponseEntity<java.util.List<Media.Slot>> list(Principal p,@PathVariable String kind,@PathVariable UUID target) { return response(media.list(UUID.fromString(p.getName()),kind,target)); }
    @GetMapping("/api/media/public/{kind}/{slug}") public ResponseEntity<java.util.List<Media.Slot>> published(@PathVariable String kind,@PathVariable String slug) { return response(media.published(kind,slug)); }
    @GetMapping("/api/media/{asset}/content") public ResponseEntity<byte[]> content(Principal p,@PathVariable UUID asset) { return image(media.content(p==null?null:UUID.fromString(p.getName()),asset)); }
    @GetMapping("/api/media/owner/{asset}/preview") public ResponseEntity<byte[]> preview(Principal p,@PathVariable UUID asset) {
        return image(media.ownerPreview(p==null?null:UUID.fromString(p.getName()),asset));
    }
    private ResponseEntity<byte[]> image(byte[] bytes){return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Content-Type","image/png").header("Content-Disposition","inline; filename=\"image.png\"").body(bytes);}
    @PutMapping(path="/api/media/{kind}/{target}/{slot}",consumes="multipart/form-data") public ResponseEntity<Media.Slot> upload(Principal p,@PathVariable String kind,@PathVariable UUID target,@PathVariable int slot,@RequestParam String version,@RequestParam String alt,@RequestParam MultipartFile file) throws java.io.IOException {
        if(file.getSize()>5*1024*1024) { throw new MediaException(400); }
        return response(media.upload(UUID.fromString(p.getName()),kind,target,slot,version(version),alt,file.getOriginalFilename(),file.getBytes()));
    }
    @DeleteMapping("/api/media/{kind}/{target}/{slot}") public ResponseEntity<Media.Slot> delete(Principal p,@PathVariable String kind,@PathVariable UUID target,@PathVariable int slot,@RequestParam String version) { return response(media.delete(UUID.fromString(p.getName()),kind,target,slot,version(version))); }
    private long version(String value) { if(value==null||!value.matches("0|[1-9][0-9]{0,15}")) { throw new MediaException(400); } try { return Long.parseLong(value); } catch(NumberFormatException failure) { throw new MediaException(400); } }
    private <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().header("Cache-Control","no-store").body(value); }
}
