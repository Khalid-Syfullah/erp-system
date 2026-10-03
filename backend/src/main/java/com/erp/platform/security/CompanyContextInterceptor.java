package com.erp.platform.security;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.RequestLoggingFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Company membership (SECURITY.md §4.4 layer 2): for every route with a {@code {companyId}} path
 * variable, the actor must have a valid role assignment in that company (and an API token's company
 * restriction must match). Otherwise the answer is 404, so the existence of other companies is not
 * disclosed. On success the active company and branch scope are put into the {@link RequestContext};
 * transactions then run with the company's RLS context.
 */
public class CompanyContextInterceptor implements HandlerInterceptor {

    public static final String COMPANY_ID_VARIABLE = "companyId";

    private final ObjectProvider<CompanyAccessResolver> resolver;

    public CompanyContextInterceptor(ObjectProvider<CompanyAccessResolver> resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)
                || !(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)
                        instanceof Map<?, ?> variables)
                || !(variables.get(COMPANY_ID_VARIABLE) instanceof String rawCompanyId)) {
            return true;
        }
        UUID companyId = parse(rawCompanyId);
        RequestContext context = CurrentContext.get().orElseThrow(ApiException::notFound);
        AuthenticatedActor actor = context.actor();
        if (actor == null
                || (actor.companyRestriction() != null
                        && !actor.companyRestriction().equals(companyId))) {
            throw ApiException.notFound();
        }
        CompanyAccessResolver port = resolver.getIfAvailable();
        Optional<CompanyAccess> access = port == null ? Optional.empty() : port.resolve(actor, companyId);
        CompanyAccess granted = access.orElseThrow(ApiException::notFound);
        CurrentContext.set(context.withCompany(companyId, granted.branchScope()));
        MDC.put(RequestLoggingFilter.MDC_COMPANY_ID, companyId.toString());
        return true;
    }

    private static UUID parse(String raw) {
        try {
            if (raw.length() != 36) {
                throw ApiException.notFound();
            }
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound();
        }
    }
}
