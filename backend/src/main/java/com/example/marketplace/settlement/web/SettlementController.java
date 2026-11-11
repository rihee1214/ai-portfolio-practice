package com.example.marketplace.settlement.web;

import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.settlement.api.Settlements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("db")
public class SettlementController {
    private final Settlements service;
    private final com.example.marketplace.settlement.infrastructure.SellerSettlementRead sellerRead;
    public SettlementController(Settlements service,com.example.marketplace.settlement.infrastructure.SellerSettlementRead sellerRead) {
        this.service=service;this.sellerRead=sellerRead;
    }
    @GetMapping("/api/seller/settlements")
    public List<com.example.marketplace.settlement.infrastructure.SellerSettlementRead.Week> own(HttpServletRequest request) {
        return sellerRead.read(actor(request));
    }
    @ModelAttribute void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    private Settlements.Actor actor(HttpServletRequest request) {
        var principal=request.getUserPrincipal();var session=request.getSession(false);
        return new Settlements.Actor(principal==null?null:UUID.fromString(principal.getName()),session==null?null:session.getId(),(Long)request.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE));
    }
    @GetMapping("/api/admin/settlements/policy")
    public Settlements.Policy policy(HttpServletRequest request) { return service.policy(actor(request)); }
    @GetMapping("/api/admin/settlements/batches")
    public List<Settlements.Summary> list(HttpServletRequest request) { return service.list(actor(request)); }
    @GetMapping("/api/admin/settlements/batches/{id}")
    public Settlements.Batch get(@PathVariable UUID id,HttpServletRequest request) { return service.get(id,actor(request)); }
    private void fields(Map<String,Object> input,Set<String> fields) {
        if(!input.keySet().equals(fields)) { throw new OrderRejected(400); }
    }
    private Settlements.Change change(Map<String,Object> input) {
        fields(input,Set.of("key","expectedVersion"));
        try {
            Object version=input.get("expectedVersion");
            if(!(version instanceof Number)||!version.toString().matches("[0-9]+")) { throw new IllegalArgumentException(); }
            return new Settlements.Change(UUID.fromString((String)input.get("key")),Long.parseLong(version.toString()));
        }catch(IllegalArgumentException|ClassCastException|NullPointerException invalid) { throw new OrderRejected(400); }
    }
    @PostMapping("/api/admin/settlements/batches")
    public Settlements.Batch open(@RequestBody Map<String,Object> input,HttpServletRequest request) {
        fields(input,Set.of("key","weekStart"));Settlements.Open command;
        try { command=new Settlements.Open(UUID.fromString((String)input.get("key")),LocalDate.parse((String)input.get("weekStart"))); }
        catch(IllegalArgumentException|java.time.DateTimeException|ClassCastException|NullPointerException invalid) { throw new OrderRejected(400); }
        return service.open(command,actor(request));
    }
    @PostMapping("/api/admin/settlements/batches/{id}/review")
    public Settlements.Batch review(@PathVariable UUID id,@RequestBody Map<String,Object> input,HttpServletRequest request) { return service.review(id,change(input),actor(request)); }
    @PostMapping("/api/admin/settlements/batches/{id}/simulate-pay")
    public Settlements.Batch pay(@PathVariable UUID id,@RequestBody Map<String,Object> input,HttpServletRequest request) { return service.simulatePay(id,change(input),actor(request)); }
}
