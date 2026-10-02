package com.erp.platform.web;

import java.util.List;
import tools.jackson.core.JacksonException;

/** Builds RFC 6901 JSON Pointers for error responses. */
final class JsonPointers {

    private JsonPointers() {}

    /** Jackson reference path → pointer, e.g. {@code [lines, 2, quantity]} → {@code /lines/2/quantity}. */
    static String fromJacksonPath(List<JacksonException.Reference> path) {
        StringBuilder pointer = new StringBuilder();
        for (JacksonException.Reference reference : path) {
            pointer.append('/');
            if (reference.getPropertyName() != null) {
                pointer.append(escape(reference.getPropertyName()));
            } else if (reference.getIndex() >= 0) {
                pointer.append(reference.getIndex());
            } else {
                pointer.append('-');
            }
        }
        return pointer.toString();
    }

    /** Bean property path → pointer, e.g. {@code lines[2].quantity} → {@code /lines/2/quantity}. */
    static String fromPropertyPath(String propertyPath) {
        if (propertyPath == null || propertyPath.isEmpty()) {
            return "";
        }
        StringBuilder pointer = new StringBuilder();
        for (String segment : propertyPath.replace("[", ".").replace("]", "").split("\\.")) {
            if (!segment.isEmpty()) {
                pointer.append('/').append(escape(segment));
            }
        }
        return pointer.toString();
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
