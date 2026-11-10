package com.example.marketplace.content.web;

import com.example.marketplace.content.infrastructure.ProductQuestions;
import com.example.marketplace.media.api.MessageImages;
import com.example.marketplace.media.api.MediaException;
import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.operations.api.SupportAssignments;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("db")
@RequestMapping("/api/questions/{id}/messages/{msg}/images")
@Transactional
public class ProductImagesController {
    private final ProductQuestions cases;
    private final ObjectProvider<MessageImages> images;
    public ProductImagesController(ProductQuestions cases,ObjectProvider<MessageImages> images) {
        this.cases=cases;this.images=images;
    }
    private SupportAssignments.Actor actor(HttpServletRequest request) {
        var p=request.getUserPrincipal();var s=request.getSession(false);
        return new SupportAssignments.Actor(p==null?null:UUID.fromString(p.getName()),s==null?null:s.getId(),
                (Long)request.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE));
    }
    @GetMapping
    public ResponseEntity<MessageImages.View> list(@PathVariable UUID id,@PathVariable UUID msg,HttpServletRequest request) {
        var target=cases.messageAccess(id,msg,actor(request),request.isUserInRole("SELLER"),false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(images.getObject().forMessage(target));
    }
    @GetMapping("/{asset}")
    public ResponseEntity<byte[]> read(@PathVariable UUID id,@PathVariable UUID msg,@PathVariable UUID asset,
            HttpServletRequest request) {
        var target=cases.messageAccess(id,msg,actor(request),request.isUserInRole("SELLER"),false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .body(images.getObject().readMessageImage(target,asset));
    }
    @RequestMapping(path="/{slot}",method={RequestMethod.POST,RequestMethod.DELETE})
    public MessageImages.Receipt change(@PathVariable UUID id,@PathVariable UUID msg,@PathVariable int slot,
            @RequestParam UUID key,@RequestParam long version,@RequestParam(required=false) MultipartFile file,
            HttpServletRequest request) throws java.io.IOException {
        boolean upload=request.getMethod().equals("POST");
        if(upload!=(file!=null) || file!=null && file.getSize()>5242880) { throw new MediaException(400); }
        var target=cases.messageAccess(id,msg,actor(request),request.isUserInRole("SELLER"),true);
        UUID member=actor(request).member(),guest=null;
        return images.getObject().changeMessageImage(target,member,guest,key,version,slot,
                file==null?null:file.getOriginalFilename(),file==null?null:file.getBytes());
    }
}
