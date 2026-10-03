package com.erp.platform.crypto;

import com.erp.platform.config.ErpProperties;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class CryptoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CryptoConfiguration.class);

    /**
     * Keys come from {@code ERP_FIELD_ENCRYPTION_KEYS} (required in production, see
     * RequiredConfigurationValidator). Outside production a missing value yields an ephemeral key:
     * encrypted values (e.g. TOTP secrets) then become unreadable after a restart.
     */
    @Bean
    FieldEncryptor fieldEncryptor(ErpProperties properties, Environment environment) {
        String configured = properties.crypto().fieldEncryptionKeys();
        if (configured == null || configured.isBlank()) {
            if (!environment.matchesProfiles("migrate")) {
                log.warn("No field encryption keys configured (ERP_FIELD_ENCRYPTION_KEYS); using an ephemeral key");
            }
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            return new FieldEncryptor(Map.of(1, key));
        }
        return new FieldEncryptor(parse(configured));
    }

    static Map<Integer, byte[]> parse(String configured) {
        Map<Integer, byte[]> keys = new HashMap<>();
        for (String entry : configured.split(",")) {
            String[] parts = entry.strip().split(":", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("ERP_FIELD_ENCRYPTION_KEYS entries must be <version>:<base64 key>");
            }
            int version;
            byte[] key;
            try {
                version = Integer.parseInt(parts[0].strip());
                key = Base64.getDecoder().decode(parts[1].strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("ERP_FIELD_ENCRYPTION_KEYS contains an invalid entry", e);
            }
            if (keys.put(version, key) != null) {
                throw new IllegalStateException("ERP_FIELD_ENCRYPTION_KEYS repeats key version " + version);
            }
        }
        return keys;
    }
}
