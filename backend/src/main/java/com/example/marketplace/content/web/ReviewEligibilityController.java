package com.example.marketplace.content.web;

import com.example.marketplace.content.domain.ReviewEligibility;
import com.example.marketplace.content.infrastructure.ReviewCommands;
import com.example.marketplace.content.infrastructure.ReviewQueries;
import com.example.marketplace.content.infrastructure.ReviewResponses;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.ReviewPurchases;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@Profile("db")
public class ReviewEligibilityController {
    public record Eligibility(boolean eligible, String reason, ReviewQueries.Entry existing) { }
    private final ReviewPurchases purchases;
    private final ReviewCommands commands;
    private final ReviewQueries queries;
    private final ReviewResponses responses;

    public ReviewEligibilityController(ReviewPurchases purchases, ReviewCommands commands, ReviewQueries queries, ReviewResponses responses) {
        this.purchases = purchases;
        this.commands = commands;
        this.queries = queries;
        this.responses = responses;
    }

    @GetMapping("/api/orders/{number}/items/{itemId}/review-eligibility")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Eligibility> eligibility(@PathVariable String number, @PathVariable UUID itemId,
            Principal principal, HttpSession session, HttpServletRequest request) {
        var item = purchases.get(number, itemId, authority(principal, session, request));
        var units = item.units().stream().map(unit ->
                new ReviewEligibility.Unit(unit.confirmation() != null, unit.refundCause() != null)).toList();
        boolean eligible = ReviewEligibility.mayWrite(units);
        String reason = eligible ? "CONFIRMED_PURCHASE" : units.stream().anyMatch(ReviewEligibility.Unit::confirmed)
                ? "NO_RETAINED_CONFIRMED_UNIT" : "NO_CONFIRMATION";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Eligibility(eligible, reason, queries.own(item.memberId(), itemId)));
    }

    @GetMapping("/api/products/{slug}/reviews")
    public ResponseEntity<ReviewQueries.Published> published(@PathVariable String slug, Principal principal, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.published(slug,
                principal == null ? null : UUID.fromString(principal.getName()), request.isUserInRole("SELLER")));
    }

    @PostMapping("/api/orders/{number}/items/{itemId}/review")
    public ResponseEntity<ReviewCommands.Receipt> change(@PathVariable String number, @PathVariable UUID itemId,
            Principal principal, HttpSession session, HttpServletRequest request,
            @RequestBody tools.jackson.databind.JsonNode input) {
        ReviewCommands.Command command;
        try {
            if (!input.isObject() || !text(input,"key") || !text(input,"action") || !integer(input,"expectedVersion")) {
                throw new IllegalArgumentException();
            }
            var action = ReviewCommands.Action.valueOf(input.get("action").asText());
            boolean deleting = action == ReviewCommands.Action.DELETE;
            if (input.size() != (deleting ? 3 : 5) || !deleting && (!integer(input,"rating")
                    || !input.get("rating").canConvertToInt() || !text(input,"body"))) { throw new IllegalArgumentException(); }
            command = new ReviewCommands.Command(UUID.fromString(input.get("key").asText()),action,
                    input.get("expectedVersion").asLong(),deleting ? null : input.get("rating").asInt(),
                    deleting ? null : input.get("body").asText());
        } catch (IllegalArgumentException invalid) { throw new com.example.marketplace.order.api.OrderRejected(400); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(commands.apply(number, itemId, authority(principal, session, request), command));
    }

    private static boolean text(tools.jackson.databind.JsonNode input,String field) {
        return input.has(field) && input.get(field).isTextual();
    }
    private static boolean integer(tools.jackson.databind.JsonNode input,String field) {
        return input.has(field) && input.get(field).isIntegralNumber() && input.get(field).canConvertToLong();
    }

    private static OrderQueries.Authority authority(Principal principal, HttpSession session, HttpServletRequest request) {
        var member = principal == null ? null : UUID.fromString(principal.getName());
        return new OrderQueries.Authority(session.getId(), member,
                (Long) request.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE), null);
    }

    @PostMapping("/api/reviews/{id}/responses")
    public ReviewResponses.Receipt respond(@PathVariable UUID id, @RequestBody ReviewResponses.Command command,
            Principal principal, HttpSession session, HttpServletRequest request) {
        return responses.apply(id, authority(principal, session, request), command);
    }

    @GetMapping("/api/reviews/{id}/images/{asset}")
    public ResponseEntity<byte[]> image(@PathVariable UUID id, @PathVariable UUID asset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(org.springframework.http.MediaType.IMAGE_PNG)
                .body(queries.image(id, asset));
    }

    @GetMapping("/api/reviews/{id}")
    public ResponseEntity<ReviewQueries.PublicEntry> detail(@PathVariable UUID id, Principal principal, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.detail(id,
                principal == null ? null : UUID.fromString(principal.getName()), request.isUserInRole("SELLER")));
    }

    @PostMapping("/api/orders/{number}/items/{itemId}/review/images/{slot}")
    public ReviewCommands.Receipt upload(@PathVariable String number, @PathVariable UUID itemId, @PathVariable int slot,
            @RequestParam UUID key, @RequestParam long version, @RequestParam MultipartFile file,
            Principal principal, HttpSession session, HttpServletRequest request) throws java.io.IOException {
        if (file.getSize() > 5242880) { throw new com.example.marketplace.order.api.OrderRejected(400); }
        return commands.image(number, itemId, authority(principal, session, request), key, version, slot,
                file.getOriginalFilename(), file.getBytes());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/orders/{number}/items/{itemId}/review/images/{slot}")
    public ReviewCommands.Receipt remove(@PathVariable String number, @PathVariable UUID itemId, @PathVariable int slot,
            @RequestParam UUID key, @RequestParam long version, Principal principal, HttpSession session, HttpServletRequest request) {
        return commands.image(number, itemId, authority(principal, session, request), key, version, slot, null, null);
    }
}
