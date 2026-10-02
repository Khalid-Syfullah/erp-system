package com.erp.platform.security;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Handler-level authorization (SECURITY.md §4.4, layer 3). Fails closed: a handler without an access
 * annotation, or a permission check without a registered {@link PermissionCheck}, is denied.
 */
public class EndpointAccessInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(EndpointAccessInterceptor.class);

    private final ObjectProvider<PermissionCheck> permissionCheck;

    public EndpointAccessInterceptor(ObjectProvider<PermissionCheck> permissionCheck) {
        this.permissionCheck = permissionCheck;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        if (EndpointAnnotations.isPublic(method)
                || EndpointAnnotations.find(method, AuthenticatedEndpoint.class) != null) {
            return true;
        }
        RequiresPermission required = EndpointAnnotations.find(method, RequiresPermission.class);
        if (required == null) {
            log.error("Endpoint {} has no access annotation; denying", method.getShortLogMessage());
            throw forbidden();
        }
        PermissionCheck check = permissionCheck.getIfAvailable();
        RequestContext context = CurrentContext.get().orElse(null);
        if (check == null || context == null) {
            throw forbidden();
        }
        for (String permission : required.value()) {
            if (!check.isGranted(context, permission)) {
                throw forbidden();
            }
        }
        return true;
    }

    private static ApiException forbidden() {
        return new ApiException(PlatformErrorCode.FORBIDDEN, "You do not have permission to perform this action.");
    }
}
