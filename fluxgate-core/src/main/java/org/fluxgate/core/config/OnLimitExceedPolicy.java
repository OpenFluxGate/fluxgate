package org.fluxgate.core.config;

/**
 * Defines how the engine should behave when a limit is exceeded.
 */
public enum OnLimitExceedPolicy {

    /**
     * Immediately reject the request.
     */
    REJECT_REQUEST,

    /**
     * Block the caller until enough tokens are available.
     * (Actual waiting logic will be implemented at the server API level.)
     */
    WAIT_FOR_REFILL
}