package com.erp.platform.web.paging;

import com.erp.platform.config.ErpProperties;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class PagingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PagingConfiguration.class);

    /**
     * The cursor signing key comes from {@code ERP_API_CURSOR_SIGNING_KEY} (required in production, see
     * RequiredConfigurationValidator). Elsewhere a missing key is replaced by a random one per process,
     * which only means cursors do not survive a restart.
     */
    @Bean
    CursorCodec cursorCodec(ErpProperties properties, Environment environment) {
        String configured = properties.api().cursorSigningKey();
        if (configured == null || configured.isBlank()) {
            if (!environment.matchesProfiles("migrate")) {
                log.warn("No cursor signing key configured (ERP_API_CURSOR_SIGNING_KEY); using an ephemeral key");
            }
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            return new CursorCodec(key);
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(configured.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ERP_API_CURSOR_SIGNING_KEY must be Base64-encoded", e);
        }
        if (key.length < 32) {
            throw new IllegalStateException("ERP_API_CURSOR_SIGNING_KEY must decode to at least 32 bytes");
        }
        return new CursorCodec(key);
    }

    @Bean
    ListQueryParser listQueryParser(CursorCodec cursorCodec) {
        return new ListQueryParser(cursorCodec);
    }
}
