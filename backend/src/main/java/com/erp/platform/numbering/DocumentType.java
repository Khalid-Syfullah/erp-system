package com.erp.platform.numbering;

import java.util.regex.Pattern;

/**
 * A numbered document type, declared by its module as a Spring bean. The default format applies
 * until the company configures its own ({@code PUT {c}/settings/numbering}).
 *
 * @param defaultPrefix prefix template; {@code {FY}} is replaced by the fiscal year label
 */
public record DocumentType(String code, String defaultPrefix, int defaultPadding) {

    static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_:]{1,49}$");

    public DocumentType {
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid document type code: " + code);
        }
        NumberFormat.validate(defaultPrefix, defaultPadding);
    }
}
