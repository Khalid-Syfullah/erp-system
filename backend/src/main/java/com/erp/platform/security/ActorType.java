package com.erp.platform.security;

/** How the current principal authenticated (also the audit log's {@code actor_type}). */
public enum ActorType {
    /** A person with a browser session. */
    USER,
    /** An integration (personal or service-account API token). */
    API_TOKEN,
    /** Background jobs and one-shot maintenance commands. */
    SYSTEM
}
