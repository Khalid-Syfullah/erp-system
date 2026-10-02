package com.erp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.modulith.Modulithic;
import org.springframework.web.context.WebApplicationContext;

/**
 * ERP backend: a modular monolith (ARCHITECTURE.md). Each top-level package below {@code com.erp}
 * is an application module; {@code platform} is the shared kernel.
 */
@Modulithic(systemName = "ERP", sharedModules = "platform")
// No in-memory default user: authentication arrives with the Auth module (Phase 3), and Boot's
// fallback would log a generated password.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class ErpApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(ErpApplication.class, args);
        if (!(context instanceof WebApplicationContext)) {
            // One-shot modes without a web server (the "migrate" job) have done their work once the
            // context has started; exit so the job completes instead of idling on scheduler threads.
            System.exit(SpringApplication.exit(context));
        }
    }
}
