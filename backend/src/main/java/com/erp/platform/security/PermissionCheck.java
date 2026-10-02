package com.erp.platform.security;

import com.erp.platform.context.RequestContext;

/**
 * Port for permission decisions (ARCHITECTURE.md §4.3). Implemented by the Auth module (Phase 3) and
 * wired at the composition root. While no implementation is registered every
 * {@link RequiresPermission} check is denied (fail closed).
 */
public interface PermissionCheck {

    boolean isGranted(RequestContext context, String permission);
}
