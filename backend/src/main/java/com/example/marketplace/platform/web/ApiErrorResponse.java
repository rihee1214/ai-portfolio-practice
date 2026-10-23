package com.example.marketplace.platform.web;

/** Public error data only: never use exception messages or rejected input here. */
public record ApiErrorResponse(String code, String message, String requestId) {
    public static ApiErrorResponse forStatus(int status, String requestId) {
        return switch (status) {
            case 400 -> new ApiErrorResponse("INVALID_REQUEST", "요청 내용을 확인해 주세요.", requestId);
            case 401 -> new ApiErrorResponse("AUTHENTICATION_REQUIRED", "로그인이 필요합니다.", requestId);
            case 403 -> new ApiErrorResponse("FORBIDDEN", "요청을 처리할 권한이 없습니다.", requestId);
            case 404 -> new ApiErrorResponse("NOT_FOUND", "요청한 대상을 찾을 수 없습니다.", requestId);
            case 405 -> new ApiErrorResponse("METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다.", requestId);
            case 406 -> new ApiErrorResponse("NOT_ACCEPTABLE", "요청한 응답 형식을 지원하지 않습니다.", requestId);
            case 409 -> new ApiErrorResponse("CONFLICT", "현재 상태에서는 요청을 처리할 수 없습니다.", requestId);
            case 415 -> new ApiErrorResponse("UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 형식입니다.", requestId);
            case 429 -> new ApiErrorResponse("RATE_LIMITED", "요청이 많습니다. 잠시 후 다시 시도해 주세요.", requestId);
            default -> status >= 500
                    ? new ApiErrorResponse("INTERNAL_ERROR", "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", requestId)
                    : new ApiErrorResponse("REQUEST_FAILED", "요청을 처리할 수 없습니다.", requestId);
        };
    }
}
