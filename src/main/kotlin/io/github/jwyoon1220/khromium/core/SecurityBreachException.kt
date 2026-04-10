package io.github.jwyoon1220.khromium.core

/**
 * Thrown when a critical security invariant is violated — most commonly when the
 * heap canary guard zone is found corrupted, indicating a buffer-overflow attack.
 *
 * This exception is caught at the tab boundary so that only the offending tab is
 * terminated while the Khromium kernel and all other tabs keep running.
 */
class SecurityBreachException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
