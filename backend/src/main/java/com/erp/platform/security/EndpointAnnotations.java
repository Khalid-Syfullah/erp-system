package com.erp.platform.security;

import java.lang.annotation.Annotation;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

/** Resolves the access annotation of a handler: method level first, then class level. */
final class EndpointAnnotations {

    private EndpointAnnotations() {}

    static <A extends Annotation> @Nullable A find(HandlerMethod handler, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), type);
    }

    static boolean isPublic(HandlerMethod handler) {
        return find(handler, PublicEndpoint.class) != null;
    }
}
