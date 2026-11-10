package com.example.marketplace.content.domain;

public final class InquiryText {
    private InquiryText() { }

    public static String normalize(String raw) {
        if (raw == null) { throw new IllegalArgumentException("INQUIRY_BODY_REQUIRED"); }
        String text = raw.strip();
        if (text.isBlank() || text.codePointCount(0, text.length()) > 2000) {
            throw new IllegalArgumentException("INQUIRY_BODY_LENGTH");
        }
        return text;
    }
}
