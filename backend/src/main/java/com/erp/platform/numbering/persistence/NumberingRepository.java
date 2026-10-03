package com.erp.platform.numbering.persistence;

import static com.erp.db.platform.Tables.DOCUMENT_SEQUENCES;
import static com.erp.db.platform.Tables.NUMBERING_SETTINGS;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

/** Number sequences and number format settings (DATABASE.md §5.1). */
@Repository
public class NumberingRepository {

    /** The stored formats JSON of a company and its version. */
    public record Settings(String formats, int version) {}

    private final DSLContext dsl;

    public NumberingRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Takes the next value of the sequence. The row stays locked until the caller's transaction ends,
     * so numbers are gapless: a rollback returns the number (DATABASE.md §9).
     */
    public long allocate(UUID companyId, String documentType, String scopeKey, String prefix, int padding) {
        dsl.insertInto(DOCUMENT_SEQUENCES)
                .set(DOCUMENT_SEQUENCES.COMPANY_ID, companyId)
                .set(DOCUMENT_SEQUENCES.DOCUMENT_TYPE, documentType)
                .set(DOCUMENT_SEQUENCES.SCOPE_KEY, scopeKey)
                .set(DOCUMENT_SEQUENCES.PREFIX, prefix)
                .set(DOCUMENT_SEQUENCES.PADDING, (short) padding)
                .onConflictDoNothing()
                .execute();
        long value = dsl.select(DOCUMENT_SEQUENCES.NEXT_VALUE)
                .from(DOCUMENT_SEQUENCES)
                .where(DOCUMENT_SEQUENCES.COMPANY_ID.eq(companyId))
                .and(DOCUMENT_SEQUENCES.DOCUMENT_TYPE.eq(documentType))
                .and(DOCUMENT_SEQUENCES.SCOPE_KEY.eq(scopeKey))
                .forUpdate()
                .fetchSingle(DOCUMENT_SEQUENCES.NEXT_VALUE);
        dsl.update(DOCUMENT_SEQUENCES)
                .set(DOCUMENT_SEQUENCES.NEXT_VALUE, value + 1)
                .set(DOCUMENT_SEQUENCES.PREFIX, prefix)
                .set(DOCUMENT_SEQUENCES.PADDING, (short) padding)
                .set(DOCUMENT_SEQUENCES.UPDATED_AT, OffsetDateTime.now())
                .where(DOCUMENT_SEQUENCES.COMPANY_ID.eq(companyId))
                .and(DOCUMENT_SEQUENCES.DOCUMENT_TYPE.eq(documentType))
                .and(DOCUMENT_SEQUENCES.SCOPE_KEY.eq(scopeKey))
                .execute();
        return value;
    }

    public Optional<Settings> settings(UUID companyId) {
        return dsl.select(NUMBERING_SETTINGS.FORMATS, NUMBERING_SETTINGS.VERSION)
                .from(NUMBERING_SETTINGS)
                .where(NUMBERING_SETTINGS.COMPANY_ID.eq(companyId))
                .fetchOptional(r -> new Settings(r.value1().data(), r.value2()));
    }

    /** Creates or replaces the formats; {@code false} when the expected version no longer matches. */
    public boolean saveSettings(UUID companyId, int expectedVersion, String formats, UUID actor) {
        if (expectedVersion == 0) {
            int inserted = dsl.insertInto(NUMBERING_SETTINGS)
                    .set(NUMBERING_SETTINGS.COMPANY_ID, companyId)
                    .set(NUMBERING_SETTINGS.FORMATS, JSONB.jsonb(formats))
                    .set(NUMBERING_SETTINGS.VERSION, 1)
                    .set(NUMBERING_SETTINGS.CREATED_BY, actor)
                    .set(NUMBERING_SETTINGS.UPDATED_BY, actor)
                    .onConflictDoNothing()
                    .execute();
            return inserted == 1;
        }
        return dsl.update(NUMBERING_SETTINGS)
                        .set(NUMBERING_SETTINGS.FORMATS, JSONB.jsonb(formats))
                        .set(NUMBERING_SETTINGS.VERSION, expectedVersion + 1)
                        .set(NUMBERING_SETTINGS.UPDATED_AT, OffsetDateTime.now())
                        .set(NUMBERING_SETTINGS.UPDATED_BY, actor)
                        .where(NUMBERING_SETTINGS.COMPANY_ID.eq(companyId))
                        .and(NUMBERING_SETTINGS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }
}
