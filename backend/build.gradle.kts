import org.flywaydb.core.Flyway
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.ForcedType
import org.jooq.meta.jaxb.Generate
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.SchemaMappingType
import org.jooq.meta.jaxb.Target
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.util.Properties
import java.util.UUID

buildscript {
    dependencies {
        // Build-time classpath for jOOQ code generation (see generateJooq below).
        classpath(libs.codegen.jooq)
        classpath(libs.codegen.jooq.postgres.extensions)
        classpath(libs.codegen.flyway.core)
        classpath(libs.codegen.flyway.postgresql)
        classpath(libs.codegen.postgresql)
        classpath(libs.codegen.testcontainers.postgresql)
    }
    configurations.classpath { resolutionStrategy.activateDependencyLocking() }
}

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spotless)
}

group = "com.erp"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.java.get()) }
}

repositories { mavenCentral() }

dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))
    // Security overrides (see gradle/libs.versions.toml): newer patch releases than the Boot BOM manages.
    implementation(platform(libs.jackson.bom))
    constraints {
        listOf("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket").forEach { artifact ->
            implementation("org.apache.tomcat.embed:$artifact") {
                version { require(libs.versions.tomcat.get()) }
                because("CVE-2026-65182, CVE-2026-65905, CVE-2026-68525 fixed in Tomcat 11.0.25+")
            }
        }
    }

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.jooq.postgres.extensions)
    implementation(libs.spring.boot.starter.mail)
    // Argon2id for Spring Security's Argon2PasswordEncoder (SECURITY.md §3.2).
    implementation(libs.bouncycastle.bcprov)
    // Cluster-safe recurring maintenance jobs (ARCHITECTURE.md §2).
    implementation(libs.db.scheduler.starter)
    // OpenAPI document (API only, no UI). The runtime endpoint is off; OpenApiContractTest generates the spec.
    implementation(libs.springdoc.webmvc.api)
    // Only the module annotations at runtime; verification and documentation are test-only.
    implementation(libs.spring.modulith.api)
    // Compile-time access to PSQLException (constraint names in DatabaseErrorTranslator).
    implementation(libs.postgresql)
    runtimeOnly(libs.micrometer.registry.prometheus)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.spring.modulith.docs)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------------------------------------------------------------------------------------
// jOOQ code generation (ARCHITECTURE.md §2, DATABASE.md §2.6).
// Starts a throwaway PostgreSQL container, applies the role bootstrap and all Flyway
// migrations exactly as production does (migrator role + SET ROLE erp_owner), then
// generates type-safe classes into build/generated-src/jooq (never committed).
// ---------------------------------------------------------------------------------------
val bootstrapSql = rootProject.file("../infra/db/bootstrap/00-roles.sql")
val migrationsDir = file("src/main/resources/db/migration")
val jooqOutputDir = layout.buildDirectory.dir("generated-src/jooq")
val postgresImage = libs.versions.postgres.image.get()

// Opens connections through the driver instance directly. java.sql.DriverManager only hands out drivers
// registered by the caller's class loader, which breaks in a long-lived Gradle daemon once the build
// script class loader changes ("No suitable driver found").
fun connect(url: String, user: String, password: String): Connection =
    org.postgresql.Driver().connect(url, Properties().apply { setProperty("user", user); setProperty("password", password) })
        ?: error("PostgreSQL driver rejected URL $url")

val generateJooq = tasks.register("generateJooq") {
    group = "build"
    description = "Generates jOOQ classes from the Flyway-migrated schema (requires Docker)."
    inputs.file(bootstrapSql).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(migrationsDir).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("postgresImage", postgresImage)
    inputs.property("jooqVersion", libs.versions.jooq.get())
    outputs.dir(jooqOutputDir)
    outputs.cacheIf { true }

    doLast {
        val outDir = jooqOutputDir.get().asFile
        val migratorPassword = UUID.randomUUID().toString()
        PostgreSQLContainer(postgresImage).withDatabaseName("erp").use { pg ->
            pg.start()
            connect(pg.jdbcUrl, pg.username, pg.password).use { conn ->
                conn.createStatement().use { it.execute(bootstrapSql.readText()) }
                conn.createStatement().use { it.execute("ALTER ROLE erp_migrator PASSWORD '$migratorPassword'") }
            }
            Flyway.configure(Flyway::class.java.classLoader)
                .dataSource(pg.jdbcUrl, "erp_migrator", migratorPassword)
                .initSql("SET ROLE erp_owner")
                .schemas("platform")
                .defaultSchema("platform")
                .table("flyway_schema_history")
                .placeholderReplacement(false)
                .locations("filesystem:${migrationsDir.absolutePath}")
                .load()
                .migrate()

            val schemas = connect(pg.jdbcUrl, pg.username, pg.password).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT nspname FROM pg_namespace WHERE nspowner = 'erp_owner'::regrole ORDER BY nspname"
                    ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                }
            }

            GenerationTool.generate(
                Configuration()
                    .withJdbc(Jdbc().withDriver("org.postgresql.Driver").withUrl(pg.jdbcUrl)
                        .withUser(pg.username).withPassword(pg.password))
                    .withGenerator(
                        Generator()
                            .withName("org.jooq.codegen.JavaGenerator")
                            .withDatabase(
                                Database()
                                    .withName("org.jooq.meta.postgres.PostgresDatabase")
                                    .withSchemata(schemas.map { SchemaMappingType().withInputSchema(it) })
                                    // Monthly audit partitions are reached only through admin.audit_log (RLS).
                                    .withExcludes("flyway_schema_history|audit_log_[0-9]{6}")
                                    .withIncludeRoutines(false)
                                    .withIncludeTriggerRoutines(false)
                                    .withForcedTypes(
                                        // citext is bound as text; the application stores and queries
                                        // lower-cased values (auth.users.email has a CHECK for it).
                                        ForcedType().withName("VARCHAR").withIncludeTypes("citext"),
                                        ForcedType()
                                            .withUserType("org.jooq.postgres.extensions.types.Inet")
                                            .withBinding("org.jooq.postgres.extensions.bindings.InetBinding")
                                            .withIncludeTypes("inet"),
                                        ForcedType()
                                            .withUserType("org.jooq.postgres.extensions.types.Ltree")
                                            .withBinding("org.jooq.postgres.extensions.bindings.LtreeBinding")
                                            .withIncludeTypes("ltree"),
                                    )
                            )
                            .withGenerate(
                                Generate()
                                    .withRecords(true)
                                    .withPojos(false)
                                    .withDaos(false)
                                    .withJavaTimeTypes(true)
                                    .withGeneratedAnnotation(false)
                                    .withComments(true)
                            )
                            .withTarget(Target().withPackageName("com.erp.db").withDirectory(outDir.absolutePath))
                    )
            )
        }
        // Spring Modulith: the generated code is an OPEN module; ArchitectureTests restrict each
        // business module to its own schema package (com.erp.db.<schema>).
        outDir.resolve("com/erp/db/package-info.java").writeText(
            """
            |/** jOOQ classes generated from the migrated schema. Do not edit. */
            |@org.springframework.modulith.ApplicationModule(
            |        displayName = "Generated database schema",
            |        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
            |package com.erp.db;
            |""".trimMargin()
        )
    }
}

sourceSets { main { java { srcDir(generateJooq) } } }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = libs.versions.java.get().toInt()
    // Treat warnings as errors. "processing" is noise from annotation processors on the path;
    // "this-escape" and "serial" are triggered by jOOQ-generated record classes.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-this-escape", "-Xlint:-serial", "-Werror"))
}

tasks.bootJar { archiveFileName = "erp-backend.jar" }
tasks.jar { enabled = false }

tasks.test {
    useJUnitPlatform()
    systemProperty("erp.test.bootstrap-sql", bootstrapSql.absolutePath)
    systemProperty("erp.test.postgres-image", postgresImage)
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

jacoco { toolVersion = libs.versions.jacoco.get() }

// Coverage floor of the global definition of done (DEVELOPMENT_PLAN.md §3): 80 % of the lines in
// each module's domain and application packages. Generated jOOQ code is excluded.
val coverageClasses = sourceSets.main.get().output.classesDirs.asFileTree.matching { exclude("com/erp/db/**") }

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(coverageClasses)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(coverageClasses)
    violationRules {
        rule {
            element = "PACKAGE"
            includes = listOf("com.erp.*.domain", "com.erp.*.application")
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.test { finalizedBy(tasks.jacocoTestReport) }
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat(libs.versions.palantir.java.format.get())
        removeUnusedImports()
        formatAnnotations()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
