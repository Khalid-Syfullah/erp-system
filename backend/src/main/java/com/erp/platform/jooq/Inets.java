package com.erp.platform.jooq;

import java.net.InetAddress;
import org.jooq.postgres.extensions.types.Inet;
import org.jspecify.annotations.Nullable;

/** Converts client addresses to PostgreSQL {@code inet} values without any DNS lookup. */
public final class Inets {

    private Inets() {}

    /** The address, or {@code null} if absent or not an IP literal. */
    public static @Nullable Inet of(@Nullable String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        try {
            return Inet.valueOf(InetAddress.ofLiteral(address.strip()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
