package com.example.marketplace.content.web;

import com.example.marketplace.content.infrastructure.ProductQuestions;
import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.operations.api.SupportAssignments;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
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

@RestController
@Profile("db")
public class ProductQuestionController {
    private final ProductQuestions questions;
    public ProductQuestionController(ProductQuestions questions) { this.questions=questions; }
    private SupportAssignments.Actor actor(HttpServletRequest request) {
        Principal p=request.getUserPrincipal();
        var session=request.getSession(false);
        return new SupportAssignments.Actor(p==null?null:UUID.fromString(p.getName()),session==null?null:session.getId(),
                (Long)request.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE));
    }
    @GetMapping("/api/products/{slug}/questions")
    public ResponseEntity<List<ProductQuestions.View>> list(@PathVariable String slug,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(questions.list(slug,actor(request),request.isUserInRole("SELLER")));
    }
    @GetMapping("/api/questions/{id}")
    public ResponseEntity<ProductQuestions.View> get(@PathVariable UUID id,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(questions.get(id,actor(request),request.isUserInRole("SELLER")));
    }
    @PostMapping("/api/products/{slug}/questions")
    public ProductQuestions.Receipt create(@PathVariable String slug,HttpServletRequest request,@RequestBody ProductQuestions.Create cmd) {
        return questions.create(slug,actor(request),cmd);
    }
    @PostMapping("/api/questions/{id}/answer")
    public ProductQuestions.Receipt answer(@PathVariable UUID id,HttpServletRequest request,@RequestBody ProductQuestions.Answer cmd) {
        return questions.answer(id,actor(request),cmd);
    }
}
