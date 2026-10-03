package com.erp.auth.application;

/** An entry of the permission catalogue. */
public record PermissionInfo(String code, String module, String description, boolean sensitive) {}
