package com.example.marketplace.identity.web;

import com.example.marketplace.identity.api.SignupException;
import com.example.marketplace.identity.api.SignupOperations;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db & local-mailbox")
public class SignupController {
    private final SignupOperations signup;
    public SignupController(SignupOperations signup) { this.signup = signup; }
    public record SignupInput(String email, String password) { }
    public record VerifyInput(String token) { }

    @PostMapping(path = "/api/auth/signup", consumes = "application/json")
    public void signup(@RequestBody SignupInput input, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            signup.register(input.email(), input.password());
            AuthJson.write(response, 201, Map.of("status", "EMAIL_VERIFICATION_REQUIRED"));
        } catch (SignupException exception) {
            int status = switch (exception.getMessage()) {
                case "INVALID_SIGNUP" -> 400;
                case "EMAIL_ALREADY_REGISTERED" -> 409;
                default -> 503;
            };
            AuthJson.error(request, response, status);
        }
    }

    @PostMapping(path = "/api/auth/verify-email", consumes = "application/json")
    public void verify(@RequestBody VerifyInput input, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            signup.verifyEmail(input.token());
            AuthJson.write(response, 200, Map.of("status", "EMAIL_VERIFIED"));
        } catch (SignupException exception) {
            AuthJson.error(request, response, "INVALID_EMAIL_TOKEN".equals(exception.getMessage()) ? 400 : 503);
        }
    }
}
