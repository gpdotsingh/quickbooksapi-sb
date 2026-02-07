package com.quickbooks.demo.model;

/**
 * Immutable holder for the current QuickBooks realm and OAuth token.
 */
public record QuickBooksContext(String accessToken, String realmId) {

    public QuickBooksContext {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IllegalArgumentException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new IllegalArgumentException("Realm ID is required");
        }
    }

    public static QuickBooksContext of(String accessToken, String realmId) {
        return new QuickBooksContext(accessToken, realmId);
    }

    /** Token without Bearer prefix. */
    public String rawToken() {
        return accessToken.startsWith("Bearer ") ? accessToken.substring(7) : accessToken;
    }

    /** Full Authorization header value. */
    public String bearerValue() {
        return "Bearer " + rawToken();
    }
}
