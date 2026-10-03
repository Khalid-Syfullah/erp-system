package com.erp.org.api;

import java.util.UUID;

/** A branch as seen by other modules. */
public record BranchSummary(UUID id, String code, String name, boolean active) {}
