package com.example.marketplace.operations.web;

import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.operations.api.SupportAssignments;
import com.example.marketplace.operations.infrastructure.SupportCases;
import com.example.marketplace.order.api.OrderQueries;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@Profile("db")
public class SupportController {
    private final SupportCases cases;
    private final SupportAssignments assignments;
    public SupportController(SupportCases cases,SupportAssignments assignments) {
        this.cases=cases;this.assignments=assignments;
    }
    @ModelAttribute
    void privateResponse(HttpServletResponse r) { r.setHeader("Cache-Control","no-store"); }
    static OrderQueries.Authority actor(HttpServletRequest request,String token) {
        var p=request.getUserPrincipal();var s=request.getSession(false);
        return new OrderQueries.Authority(s==null?null:s.getId(),p==null?null:UUID.fromString(p.getName()),
                (Long)request.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token);
    }
    private SupportAssignments.Actor operator(HttpServletRequest request) {
        var a=actor(request,null);return new SupportAssignments.Actor(a.member(),a.browser(),a.version());
    }
    @GetMapping("/api/admin/support/queue")
    public List<SupportAssignments.Entry> queue(HttpServletRequest request) { return assignments.queue(operator(request)); }
    @PostMapping("/api/admin/support/assignments/{id}")
    public SupportAssignments.Entry assign(@PathVariable UUID id,@RequestBody SupportAssignments.Change cmd,HttpServletRequest request) {
        return assignments.assign(id,operator(request),cmd);
    }
    @PostMapping("/api/support/lost-access")
    public Map<String,String> lost(@RequestBody Lost cmd) {
        cases.open(SupportCases.Kind.LOST_ACCESS,new SupportCases.Open(cmd.key(),cmd.body(),null,null),null);
        return Map.of("message","지원을 접수했습니다.");
    }
    public record Lost(UUID key,String body) { }
    @PostMapping("/api/support/cases")
    public SupportCases.Receipt open(@RequestBody SupportCases.Open cmd,@CookieValue(name="GUEST_ACCESS",required=false) String token,HttpServletRequest request) {
        return cases.open(cmd.orderNumber()==null?SupportCases.Kind.SUPPORT:SupportCases.Kind.ORDER,cmd,actor(request,token));
    }
    @GetMapping("/api/support/cases")
    public List<SupportCases.Summary> list(@RequestParam(defaultValue="CUSTOMER") SupportCases.Reader reader,
            @RequestParam(required=false) String number,@RequestParam(required=false) UUID sellerOrder,
            @CookieValue(name="GUEST_ACCESS",required=false) String token,HttpServletRequest request) {
        return cases.list(reader,actor(request,token),number,sellerOrder);
    }
    @GetMapping("/api/support/cases/{id}")
    public SupportCases.Detail detail(@PathVariable UUID id,@RequestParam(defaultValue="CUSTOMER") SupportCases.Reader reader,
            @CookieValue(name="GUEST_ACCESS",required=false) String token,HttpServletRequest request) {
        return cases.detail(id,reader,actor(request,token));
    }
    @PostMapping("/api/support/cases/{id}/messages")
    public SupportCases.Receipt reply(@PathVariable UUID id,@RequestParam(defaultValue="CUSTOMER") SupportCases.Reader reader,
            @RequestBody SupportCases.Reply cmd,@CookieValue(name="GUEST_ACCESS",required=false) String token,HttpServletRequest request) {
        return cases.reply(id,reader,actor(request,token),cmd);
    }
}
