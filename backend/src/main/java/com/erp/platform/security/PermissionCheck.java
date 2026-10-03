package com.erp.platform.security;

import com.erp.platform.context.RequestContext;

/**
 * Port for permission decisions (ARCHITECTURE.md §4.3), implemented by Auth. With an active company
 * the actor's permissions in that company apply; on global endpoints (no company) only the system
 * administrator's global permissions apply (SECURITY.md §4.3). Without a registered implementation
 * every {@link RequiresPermission} check is denied (fail closed).
 */
public interface PermissionCheck {

    boolean isGranted(RequestContext context, String permission);
}
