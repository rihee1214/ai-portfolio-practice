package com.example.marketplace.inventory.web;
import com.example.marketplace.inventory.api.Inventory;
import com.example.marketplace.inventory.api.InventoryException;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
@RestController @Profile("db")
public class InventoryController {
    private final Inventory inventory;
    public InventoryController(Inventory inventory) { this.inventory=inventory; }
    private boolean admin(HttpServletRequest request) { return request.getRequestURI().startsWith("/api/admin/"); }
    @GetMapping({"/api/seller/products/{product}/inventory","/api/admin/content/products/{product}/inventory"})
    public ResponseEntity<Inventory.Page<Inventory.Balance>> list(Principal actor,HttpServletRequest request,@PathVariable UUID product,@RequestParam(defaultValue="0") int page) { return response(inventory.list(UUID.fromString(actor.getName()),product,admin(request),page)); }
    @GetMapping({"/api/seller/products/{product}/inventory/{sku}/history","/api/admin/content/products/{product}/inventory/{sku}/history"})
    public ResponseEntity<Inventory.Page<Inventory.Movement>> history(Principal actor,HttpServletRequest request,@PathVariable UUID product,@PathVariable UUID sku,@RequestParam(defaultValue="0") int page) { return response(inventory.history(UUID.fromString(actor.getName()),product,sku,admin(request),page)); }
    @PutMapping(path={"/api/seller/products/{product}/inventory/{sku}","/api/admin/content/products/{product}/inventory/{sku}"},consumes="application/json")
    public ResponseEntity<Inventory.Balance> adjust(Principal actor,HttpServletRequest request,@PathVariable UUID product,@PathVariable UUID sku,@RequestBody JsonNode body) {
        if(body==null||!body.isObject()||!body.path("onHand").isIntegralNumber()||!body.path("onHand").canConvertToLong()||!body.path("version").isIntegralNumber()||!body.path("version").canConvertToLong()||!body.path("reason").isString()||!body.path("requestId").isString()||!body.path("requestId").asString().matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) { throw new InventoryException(400); }
        return response(inventory.adjust(UUID.fromString(actor.getName()),product,sku,admin(request),new Inventory.Change(body.path("onHand").asLong(),body.path("version").asLong(),body.path("reason").asString(),UUID.fromString(body.path("requestId").asString()))));
    }
    private <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().header("Cache-Control","no-store").body(body); }
}
