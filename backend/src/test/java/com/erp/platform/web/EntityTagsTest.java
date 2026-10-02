package com.erp.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

class EntityTagsTest {

    @Test
    void formatsWeakTagFromVersion() {
        assertThat(EntityTags.forVersion(7)).isEqualTo("W/\"7\"");
    }

    @Test
    void acceptsCurrentVersionInWeakStrongOrListForm() {
        assertThatCode(() -> EntityTags.requireMatch("W/\"7\"", 7)).doesNotThrowAnyException();
        assertThatCode(() -> EntityTags.requireMatch("\"7\"", 7)).doesNotThrowAnyException();
        assertThatCode(() -> EntityTags.requireMatch("W/\"6\", W/\"7\"", 7)).doesNotThrowAnyException();
    }

    @Test
    void missingHeaderIsPreconditionRequired() {
        ApiException ex = catchThrowableOfType(ApiException.class, () -> EntityTags.requireMatch(" ", 1));

        assertThat(ex.errorCode()).isEqualTo(PlatformErrorCode.PRECONDITION_REQUIRED);
    }

    @Test
    void staleMalformedOrWildcardTagIsPreconditionFailed() {
        for (String header : new String[] {"W/\"6\"", "7", "*", "W/\"99999999999\""}) {
            ApiException ex = catchThrowableOfType(ApiException.class, () -> EntityTags.requireMatch(header, 7));
            assertThat(ex.errorCode()).as(header).isEqualTo(PlatformErrorCode.PRECONDITION_FAILED);
        }
    }
}
