package com.example.marketplace.operations.web;

import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.operations.infrastructure.ProductReports;
import com.example.marketplace.operations.infrastructure.ReportInvestigations;
import com.example.marketplace.operations.infrastructure.ModerationActions;
import com.example.marketplace.operations.api.ReportTargets;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@Profile("db")
public class ReportController {
    private final ProductReports reports;
    private final ReportInvestigations investigations;
    private final ModerationActions moderation;
    public ReportController(ProductReports reports,ReportInvestigations investigations,ModerationActions moderation) {
        this.reports=reports;this.investigations=investigations;this.moderation=moderation;
    }

    private ProductReports.Actor actor(HttpServletRequest request){
        var principal=request.getUserPrincipal();
        var session=request.getSession(false);
        return new ProductReports.Actor(principal==null?null:UUID.fromString(principal.getName()),session==null?null:session.getId(),
                (Long)request.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE));
    }

    @PostMapping("/api/products/{slug}/reports")
    public ResponseEntity<ProductReports.Receipt> submit(@PathVariable String slug,@RequestBody ProductReports.Submission input,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.submit(actor(request),slug,input));
    }

    @GetMapping("/api/reports/mine")
    public ResponseEntity<List<ProductReports.Summary>> mine(HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.mine(actor(request)));
    }

    @PostMapping("/api/reports/content/{kind}/{parent}/{id}/{edition}")
    public ResponseEntity<ProductReports.Receipt> submitContent(@PathVariable ReportTargets.Kind kind,@PathVariable UUID parent,
            @PathVariable UUID id,@PathVariable long edition,@RequestBody ProductReports.Submission input,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.submitContent(actor(request),
                new ReportTargets.Ref(kind,id,parent,edition),input));
    }

    @GetMapping("/api/admin/content/reports")
    public ResponseEntity<List<ProductReports.Summary>> queue(HttpServletRequest request,@RequestParam(required=false) String state,
            @RequestParam(defaultValue="0") int page){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.queue(actor(request),state,page));
    }
    @GetMapping("/api/admin/content/reports/{id}")
    public ResponseEntity<ReportInvestigations.Detail> detail(HttpServletRequest request,@PathVariable UUID id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(investigations.detail(actor(request),id));
    }
    @GetMapping("/api/admin/content/reports/{id}/source")
    public ResponseEntity<java.util.Map<String,String>> source(HttpServletRequest request,@PathVariable UUID id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("body",investigations.source(actor(request),id)));
    }
    @GetMapping("/api/admin/content/reports/{id}/evidence/{key}/image")
    public ResponseEntity<byte[]> image(HttpServletRequest request,@PathVariable UUID id,@PathVariable UUID key){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(org.springframework.http.MediaType.IMAGE_PNG)
                .body(investigations.image(actor(request),id,key));
    }
    @PostMapping("/api/admin/content/reports/{id}/commands")
    public ResponseEntity<ReportInvestigations.Saved> change(HttpServletRequest request,@PathVariable UUID id,@RequestBody ReportInvestigations.Command cmd){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(investigations.change(actor(request),id,cmd));
    }
    @GetMapping("/api/admin/content/reports/{id}/moderation")
    public ResponseEntity<ModerationActions.View> moderation(@PathVariable UUID id,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(moderation.view(actor(request),id,null));
    }
    @PostMapping("/api/admin/content/reports/{id}/moderation")
    public ResponseEntity<ModerationActions.Receipt> apply(@PathVariable UUID id,@RequestBody ModerationActions.Command cmd,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(moderation.apply(actor(request),id,null,cmd));
    }
    @GetMapping("/api/admin/content/moderation/legacy-products/{id}")
    public ResponseEntity<ModerationActions.View> legacy(@PathVariable UUID id,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(moderation.view(actor(request),null,id));
    }
    @PostMapping("/api/admin/content/moderation/legacy-products/{id}")
    public ResponseEntity<ModerationActions.Receipt> restoreLegacy(@PathVariable UUID id,@RequestBody ModerationActions.Command cmd,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(moderation.apply(actor(request),null,id,cmd));
    }
}
