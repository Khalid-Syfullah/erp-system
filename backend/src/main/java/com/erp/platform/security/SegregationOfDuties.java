package com.erp.platform.security;

import com.erp.platform.web.ApiException;
import com.erp.platform.web.PlatformErrorCode;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Reusable segregation-of-duties rule (SECURITY.md §4.6): the user performing a controlled step
 * (approve, post, assign) must differ from the user who performed the preceding step.
 */
public final class SegregationOfDuties {

    private SegregationOfDuties() {}

    /**
     * @param rule short description used in the error, e.g. "approve a purchase order you created"
     */
    public static void requireDifferentUsers(@Nullable UUID performer, @Nullable UUID previousActor, String rule) {
        if (performer != null && performer.equals(previousActor)) {
            throw new ApiException(PlatformErrorCode.SOD_VIOLATION, "Segregation of duties: you cannot " + rule + ".");
        }
    }
}
