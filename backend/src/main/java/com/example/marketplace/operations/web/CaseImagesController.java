package com.example.marketplace.operations.web;

import com.example.marketplace.operations.infrastructure.SupportCases;
import com.example.marketplace.media.api.MessageImages;
import com.example.marketplace.media.api.MediaException;

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
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("db")
@RequestMapping("/api/support/cases/{id}/messages/{msg}/images")
@Transactional
public class CaseImagesController {
    private final SupportCases cases;
    private final ObjectProvider<MessageImages> images;
    public CaseImagesController(SupportCases cases,ObjectProvider<MessageImages> images) {
        this.cases=cases;this.images=images;
    }
    private MessageImages.Target target(SupportCases.Owner checked,UUID message) {
        return new MessageImages.Target(MessageImages.Kind.CASE_MESSAGE,message);
    }
    @GetMapping
    public ResponseEntity<MessageImages.View> list(@PathVariable UUID id,@PathVariable UUID msg,@RequestParam(defaultValue="CUSTOMER") SupportCases.Reader reader,@CookieValue(name="GUEST_ACCESS",required=false) String token,HttpServletRequest request) {
        var target=target(cases.messageAccess(id,msg,reader,SupportController.actor(request,token),false),msg);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(images.getObject().forMessage(target));
    }
    @GetMapping("/{asset}")
    public ResponseEntity<byte[]> read(@PathVariable UUID id,@PathVariable UUID msg,@PathVariable UUID asset,
            @RequestParam(defaultValue="CUSTOMER") SupportCases.Reader reader,@CookieValue(name="GUEST_ACCESS",required=false) String token,HttpServletRequest request) {
        var target=target(cases.messageAccess(id,msg,reader,SupportController.actor(request,token),false),msg);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .body(images.getObject().readMessageImage(target,asset));
    }
    @RequestMapping(path="/{slot}",method={RequestMethod.POST,RequestMethod.DELETE})
    public MessageImages.Receipt change(@PathVariable UUID id,@PathVariable UUID msg,@PathVariable int slot,
            @RequestParam UUID key,@RequestParam long version,@RequestParam(required=false) MultipartFile file,
            @RequestParam(defaultValue="CUSTOMER") SupportCases.Reader reader,@CookieValue(name="GUEST_ACCESS",required=false) String token,HttpServletRequest request) throws java.io.IOException {
        boolean upload=request.getMethod().equals("POST");
        if(upload!=(file!=null) || file!=null && file.getSize()>5242880) { throw new MediaException(400); }
        var user=cases.messageAccess(id,msg,reader,SupportController.actor(request,token),true);
        var target=target(user,msg);
        UUID member=user.member(),guest=user.guest();
        return images.getObject().changeMessageImage(target,member,guest,key,version,slot,
                file==null?null:file.getOriginalFilename(),file==null?null:file.getBytes());
    }
}
