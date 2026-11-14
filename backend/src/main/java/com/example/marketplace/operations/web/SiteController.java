package com.example.marketplace.operations.web;

import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.operations.infrastructure.SitePublications;
import com.example.marketplace.operations.infrastructure.SiteAdministration;
import com.example.marketplace.operations.infrastructure.SitePublications.Actor;
import com.example.marketplace.operations.infrastructure.SitePublications.Edit;
import com.example.marketplace.operations.infrastructure.SitePublications.Kind;
import com.example.marketplace.order.api.OrderRejected;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController @Profile("db")
public class SiteController {
    private final SitePublications posts;
    private final SiteAdministration admin;
    public SiteController(SitePublications posts,SiteAdministration admin) { this.posts=posts;this.admin=admin; }
    @ModelAttribute void noStore(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    static Actor actor(HttpServletRequest request) {
        var principal=request.getUserPrincipal();var session=request.getSession(false);
        return new Actor(principal==null?null:UUID.fromString(principal.getName()),session==null?null:session.getId(),
                (Long)request.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE));
    }
    @GetMapping("/api/site/posts") public Object list(@RequestParam Kind kind,@RequestParam(defaultValue="0") int page) { return posts.list(kind,page,null); }
    @GetMapping("/api/site/info") public Object info() { return admin.current(); }
    @GetMapping("/api/admin/system/site-info") public Object history(@RequestParam(defaultValue="9223372036854775807") long before,HttpServletRequest request) {
        return admin.history(actor(request),before);
    }
    @PostMapping("/api/admin/system/site-info") public Object update(@RequestBody JsonNode input,HttpServletRequest request) {
        checkFields(input,INFO_FIELDS);
        return admin.change(actor(request),new SiteAdministration.Change(commandKey(input),version(input),
                text(input,"siteName"),text(input,"supportGuide"),text(input,"reason")));
    }
    @GetMapping("/api/admin/system/site-jobs") public Object jobs(@RequestParam(defaultValue="FAILED") String status,@RequestParam(required=false) UUID after,HttpServletRequest request) {
        return admin.jobs(actor(request),status,after);
    }
    @GetMapping("/api/admin/system/site-jobs/{id}") public Object job(@PathVariable UUID id,HttpServletRequest request) {
        return admin.job(actor(request),id);
    }
    @GetMapping("/api/site/posts/{id}") public Object publicPost(@PathVariable UUID id) { return posts.detail(id,null); }
    @GetMapping(value="/api/site/posts/{id}/images/{asset}",produces="image/png")
    public byte[] image(@PathVariable UUID id,@PathVariable UUID asset) { return posts.image(id,asset,null); }
    @GetMapping("/api/admin/content/site-posts")
    public Object drafts(@RequestParam Kind kind,@RequestParam(defaultValue="0") int page,HttpServletRequest request) {
        return posts.list(kind,page,actor(request));
    }
    @GetMapping("/api/admin/content/site-posts/{id}/history")
    public Object history(@PathVariable UUID id,@RequestParam(defaultValue="9223372036854775807") long before,HttpServletRequest request) {
        return posts.history(id,before,actor(request));
    }
    @GetMapping(value="/api/admin/content/site-posts/{id}/images/{asset}",produces="image/png")
    public byte[] preview(@PathVariable UUID id,@PathVariable UUID asset,HttpServletRequest request) { return posts.image(id,asset,actor(request)); }
    @PostMapping(value="/api/admin/content/site-posts/{id}",consumes="application/json")
    public Object save(@PathVariable UUID id,@RequestBody JsonNode input,HttpServletRequest request) { return posts.edit(id,publication(input),null,null,actor(request)); }
    @PostMapping(value="/api/admin/content/site-posts/{id}",consumes="multipart/form-data")
    public Object upload(@PathVariable UUID id,@RequestPart("command") JsonNode input,@RequestPart("file") MultipartFile image,HttpServletRequest request) throws IOException {
        return posts.edit(id,publication(input),image.getOriginalFilename(),image.getBytes(),actor(request));
    }
    private static final Set<String> POST_FIELDS=Set.of("key","expectedVersion","kind","state","title","body","alt",
            "destination","priority","startsAt","endsAt","reason");
    private static final Set<String> INFO_FIELDS=Set.of("key","expectedVersion","siteName","supportGuide","reason");
    private static void checkFields(JsonNode input,Set<String> allowed) {
        if(!input.isObject()||!allowed.containsAll(input.propertyNames())) { throw new OrderRejected(400); }
    }
    private static String text(JsonNode input,String field) {
        var value=input.path(field);
        if(!value.isString()) { throw new OrderRejected(400); }
        return value.asText();
    }
    private static String optionalText(JsonNode input,String field) {
        return input.hasNonNull(field)?text(input,field):null;
    }
    private static long version(JsonNode input) {
        var value=input.path("expectedVersion");
        if(!value.isIntegralNumber()||!value.canConvertToLong()) { throw new OrderRejected(400); }
        return value.asLong();
    }
    private static UUID commandKey(JsonNode input) {
        try { return UUID.fromString(text(input,"key")); }
        catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
    }
    private static Instant instant(JsonNode input,String field) {
        String raw=optionalText(input,field);
        return raw==null?null:Instant.parse(raw);
    }
    private static Edit publication(JsonNode input) {
        checkFields(input,POST_FIELDS);
        var priority=input.path("priority");
        if(!priority.isIntegralNumber()||!priority.canConvertToInt()) { throw new OrderRejected(400); }
        try {
            return new Edit(commandKey(input),version(input),Kind.valueOf(text(input,"kind")),
                    SitePublications.State.valueOf(text(input,"state")),text(input,"title"),text(input,"body"),text(input,"alt"),
                    optionalText(input,"destination"),priority.asInt(),instant(input,"startsAt"),instant(input,"endsAt"),text(input,"reason"));
        } catch(IllegalArgumentException|DateTimeParseException invalid) { throw new OrderRejected(400); }
    }
}
