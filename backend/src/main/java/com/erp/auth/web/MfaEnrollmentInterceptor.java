package com.erp.auth.web;

import com.erp.auth.application.AuthErrorCode;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Blocks sessions that must enroll MFA from everything except {@link AllowedDuringMfaEnrollment} handlers. */
class MfaEnrollmentInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        RequestContext context = CurrentContext.get().orElse(null);
        if (handler instanceof HandlerMethod method
                && context != null
                && context.actor() != null
                && context.actor().mfaEnrollmentRequired()
                && !method.hasMethodAnnotation(AllowedDuringMfaEnrollment.class)) {
            throw new ApiException(
                    AuthErrorCode.MFA_ENROLLMENT_REQUIRED,
                    "Your roles require multi-factor authentication. Enroll an authenticator first (POST /api/v1/me/mfa/totp/setup).");
        }
        return true;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class Registration {
        @Bean
        WebMvcConfigurer mfaEnrollmentInterceptorConfigurer() {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    // Before company context and permission checks.
                    registry.addInterceptor(new MfaEnrollmentInterceptor()).order(-10);
                }
            };
        }
    }
}
