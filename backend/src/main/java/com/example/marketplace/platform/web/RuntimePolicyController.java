package com.example.marketplace.platform.web;

import com.example.marketplace.platform.api.RuntimePolicy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RuntimePolicyController {
    private final RuntimePolicy policy;
    public RuntimePolicyController(RuntimePolicy policy) { this.policy = policy; }
    @GetMapping("/api/runtime-policy")
    public ResponseEntity<java.util.Map<String, Object>> get() {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(java.util.Map.of("mode", policy.mode(), "restricted", policy.restricted()));
    }
}
