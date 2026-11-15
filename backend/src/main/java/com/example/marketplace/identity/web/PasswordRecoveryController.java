package com.example.marketplace.identity.web;

import com.example.marketplace.identity.api.PasswordRecoveryOperations;
import com.example.marketplace.identity.api.SignupException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
public class PasswordRecoveryController {
    private final PasswordRecoveryOperations recovery;
    public PasswordRecoveryController(PasswordRecoveryOperations recovery) { this.recovery = recovery; }
    public record RequestInput(String email) { }
    public record ResetInput(String token, String password) { }

    @PostMapping(path = "/api/auth/password/request", consumes = "application/json")
    public void request(@RequestBody RequestInput input, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            recovery.request(input.email());
            AuthJson.write(response, 202, Map.of("status", "PASSWORD_RESET_REQUESTED"));
        } catch (SignupException exception) {
            AuthJson.error(request, response, "INVALID_RECOVERY_INPUT".equals(exception.getMessage()) ? 400 : 503);
        } catch (DataAccessException exception) { AuthJson.error(request, response, 503); }
    }

    @PostMapping(path = "/api/auth/password/reset", consumes = "application/json")
    public void reset(@RequestBody ResetInput input, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            recovery.reset(input.token(), input.password());
            AuthJson.write(response, 200, Map.of("status", "PASSWORD_RESET"));
        } catch (SignupException exception) { AuthJson.error(request, response, 400); }
        catch (DataAccessException exception) { AuthJson.error(request, response, 503); }
    }
}
