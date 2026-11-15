package com.example.marketplace.platform.web;

import com.example.marketplace.platform.api.DemoFeatureUnavailable;
import com.example.marketplace.platform.api.RuntimePolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Inspect the raw JSON so ignored/unknown DTO properties cannot hide a mode override. */
@ControllerAdvice
public class PublicDemoBodyAdvice extends RequestBodyAdviceAdapter {
    private final RuntimePolicy policy;
    private final JsonMapper mapper;
    public PublicDemoBodyAdvice(RuntimePolicy policy, JsonMapper mapper) { this.policy = policy; this.mapper = mapper; }
    @Override public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return policy.restricted();
    }
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type type,
            Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        MediaType contentType = input.getHeaders().getContentType();
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) { return input; }
        byte[] bytes = input.getBody().readNBytes(6 * 1024 * 1024 + 1);
        if (bytes.length > 6 * 1024 * 1024) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE); }
        JsonNode body;
        try { body = mapper.readTree(bytes); }
        catch (tools.jackson.core.JacksonException malformed) {
            throw new org.springframework.http.converter.HttpMessageNotReadableException("Invalid JSON request", input);
        }
        rejectSandbox(body);
        if (body != null && body.isObject() && body.hasNonNull("guestAttemptId")) { throw new DemoFeatureUnavailable(); }
        return new HttpInputMessage() {
            public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }
    private void rejectSandbox(JsonNode node) {
        if (node == null) { return; }
        if (node.isObject()) {
            for (var property : node.properties()) {
                if (PublicDemoBoundary.modeField(property.getKey()) && property.getValue().isTextual()
                        && "SANDBOX".equalsIgnoreCase(property.getValue().asText())) { throw new DemoFeatureUnavailable(); }
                rejectSandbox(property.getValue());
            }
        } else if (node.isArray()) { node.forEach(this::rejectSandbox); }
    }
}
