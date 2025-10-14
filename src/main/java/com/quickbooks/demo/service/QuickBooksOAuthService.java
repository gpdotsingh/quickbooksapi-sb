package com.quickbooks.demo.service;

import java.lang.reflect.InvocationTargetException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.intuit.oauth2.client.OAuth2PlatformClient;
import com.intuit.oauth2.config.Environment;
import com.intuit.oauth2.config.OAuth2Config;
import com.intuit.oauth2.data.BearerTokenResponse;
import com.intuit.oauth2.exception.ConnectionException;
import com.intuit.oauth2.exception.InvalidRequestException;
import com.intuit.oauth2.exception.OAuthException;
import com.quickbooks.demo.config.QuickBooksConfig;


@Service
public class QuickBooksOAuthService {
    
    @Autowired
    private QuickBooksConfig config;
    
    private final SecureRandom secureRandom = new SecureRandom();
    
 
    public String getAuthorizationUrl() {
        try {
            // Validate configuration before proceeding
            validateOAuthConfiguration();
            
            String state = generateState();
            
            OAuth2Config oauth2Config = new OAuth2Config.OAuth2ConfigBuilder(
                config.getClientId(),
                config.getClientSecret()
            )
            .callDiscoveryAPI(getEnvironment())
            .buildConfig();
            
            List<String> scopeStrings = new ArrayList<>();
            if (config.getScopes() != null && !config.getScopes().isEmpty()) {
                for (String s : config.getScopes()) {
                    if (s != null && !s.trim().isEmpty()) {
                        scopeStrings.add(s.trim());
                    }
                }
            }
            if (scopeStrings.isEmpty()) {
                scopeStrings.add("com.intuit.quickbooks.accounting");
                scopeStrings.add("project-management.project");
            }
            
            String authUrl = oauth2Config.prepareUrlWithCustomScopes(
                scopeStrings, 
                config.getDynamicRedirectUri(), 
                state
            );
            // Always prompt for consent and login to allow account/company switch
            authUrl = appendPromptConsent(authUrl);
            authUrl = appendPromptLogin(authUrl);
            return authUrl;
            
        } catch (InvalidRequestException e) {
            // Handle specific SDK validation errors
            if (e.getMessage().contains("client_id")) {
                throw new RuntimeException("Invalid client ID: " + e.getMessage());
            } else if (e.getMessage().contains("scope")) {
                throw new RuntimeException("Invalid scope configuration: " + e.getMessage());
            } else {
                throw new RuntimeException("OAuth configuration error: " + e.getMessage());
            }
        } catch (Exception e) {
            // Handle network or other unexpected errors
            if (e.getMessage().contains("network") || e.getMessage().contains("connection")) {
                throw new RuntimeException("Unable to connect to QuickBooks OAuth service: " + e.getMessage());
            } else {
                throw new RuntimeException("Unexpected error generating OAuth URL: " + e.getMessage());
            }
        }
    }
    

    /**
     * Appends prompt=consent to the OAuth URL when not already present.
     */
    private String appendPromptConsent(String url) {
        if (url == null || url.contains("prompt=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "prompt=consent";
    }

    /**
     * Appends prompt=login to force re-authentication.
     */
    private String appendPromptLogin(String url) {
        if (url == null) return null;
        if (url.contains("prompt=login")) return url;
        // If prompt already exists (e.g., consent), append an additional prompt=login
        if (url.contains("prompt=")) {
            return url + "&prompt=login";
        }
        return url + (url.contains("?") ? "&" : "?") + "prompt=login";
    }

    /**
     * Best-effort token revocation to fully disconnect on our side.
     */
    public void revokeTokens(String token) {
        try {
            validateOAuthConfiguration();
            OAuth2Config oauth2Config = new OAuth2Config.OAuth2ConfigBuilder(
                config.getClientId(),
                config.getClientSecret()
            )
            .callDiscoveryAPI(getEnvironment())
            .buildConfig();
            OAuth2PlatformClient client = new OAuth2PlatformClient(oauth2Config);
            if (token != null && !token.trim().isEmpty()) {
                client.revokeToken(token);
            }
        } catch (ConnectionException ignore) {
            // Best effort: ignore failures so logout continues
        }
    }

    /**
     * Exchange authorization code for access token using SDK
     * Professional error handling for 3rd party developers
     */
    public Map<String, Object> exchangeCodeForToken(String authCode, String realmId) {
        try {
            // Validate required parameters
            if (authCode == null || authCode.trim().isEmpty()) {
                throw new RuntimeException("Authorization code is required");
            }
            if (realmId == null || realmId.trim().isEmpty()) {
                throw new RuntimeException("Realm ID is required");
            }
            
            validateOAuthConfiguration();
            
            OAuth2Config oauth2Config = new OAuth2Config.OAuth2ConfigBuilder(
                config.getClientId(),
                config.getClientSecret()
            )
            .callDiscoveryAPI(getEnvironment())
            .buildConfig();
            
            OAuth2PlatformClient client = new OAuth2PlatformClient(oauth2Config);
            
            BearerTokenResponse bearerTokenResponse = client.retrieveBearerTokens(
                authCode, 
                config.getDynamicRedirectUri()
            );
            
            Map<String, Object> result = new HashMap<>();
            result.put("access_token", bearerTokenResponse.getAccessToken());
            result.put("refresh_token", bearerTokenResponse.getRefreshToken());
            result.put("expires_in", bearerTokenResponse.getExpiresIn());
            result.put("realm_id", realmId);
            // Attempt to capture granted scopes if exposed by the SDK (best-effort)
            try {
                java.lang.reflect.Method m = bearerTokenResponse.getClass().getMethod("getScope");
                Object scope = m.invoke(bearerTokenResponse);
                if (scope != null) {
                    result.put("scope", scope.toString());
                }
            } catch (IllegalAccessException | IllegalArgumentException | NoSuchMethodException | SecurityException | InvocationTargetException ignore) {
                // Not all SDK versions expose scope; ignore if unavailable
            }
            
            return result;
            
        } catch (RuntimeException e) {
            // Re-throw our custom exceptions
            throw e;
        } catch (OAuthException e) {
            // Handle specific OAuth SDK errors
            String msg = e.getMessage() != null ? e.getMessage() : "";
            String full = e.toString() != null ? e.toString() : "";
            String errorMessage = (msg + " " + full).toLowerCase();
            if (errorMessage.contains("invalid_grant") || errorMessage.contains("authorization_code")) {
                throw new RuntimeException("Invalid or expired authorization code: " + msg);
            } else if (errorMessage.contains("invalid_client") || errorMessage.contains("client_id") || errorMessage.contains("401")) {
                // The SDK often logs the body separately; fall back to 401 status hint
                throw new RuntimeException("Invalid client credentials: " + msg);
            } else if (errorMessage.contains("invalid_scope")) {
                throw new RuntimeException("Invalid scope: " + msg);
            } else if (msg != null && msg.toLowerCase().contains("failed getting access token")) {
                // SDK often collapses 401 invalid_client to this generic message
                throw new RuntimeException("Invalid client credentials (invalid_client): " + msg + ". Check Client ID/Secret and Redirect URI.");
            } else {
                throw new RuntimeException("OAuth token exchange failed: " + msg);
            }
        } catch (Exception e) {
            // Handle network or other unexpected errors
            if (e.getMessage().contains("network") || e.getMessage().contains("connection") || e.getMessage().contains("timeout")) {
                throw new RuntimeException("Network error during token exchange: " + e.getMessage());
            } else {
                throw new RuntimeException("Unexpected error during token exchange: " + e.getMessage());
            }
        }
    }
    

    public Map<String, Object> refreshToken(String refreshToken) {
        try {
            // Validate required parameters
            if (refreshToken == null || refreshToken.trim().isEmpty()) {
                throw new RuntimeException();
            }
            
            validateOAuthConfiguration();
            
            OAuth2Config oauth2Config = new OAuth2Config.OAuth2ConfigBuilder(
                config.getClientId(),
                config.getClientSecret()
            )
            .callDiscoveryAPI(getEnvironment())
            .buildConfig();
            
            OAuth2PlatformClient client = new OAuth2PlatformClient(oauth2Config);
            
            BearerTokenResponse bearerTokenResponse = client.refreshToken(refreshToken);
            
            Map<String, Object> result = new HashMap<>();
            result.put("access_token", bearerTokenResponse.getAccessToken());
            result.put("refresh_token", bearerTokenResponse.getRefreshToken() != null ? 
                bearerTokenResponse.getRefreshToken() : refreshToken);
            result.put("expires_in", bearerTokenResponse.getExpiresIn());
            
            return result;
            
        } catch (RuntimeException e) {
            // Re-throw our custom exceptions
            throw e;
        } catch (OAuthException e) {
            // Handle specific OAuth SDK errors
            String errorMessage = e.getMessage().toLowerCase();
            if (errorMessage.contains("invalid_grant") || errorMessage.contains("refresh_token")) {
                throw new RuntimeException();
            } else if (errorMessage.contains("invalid_client")) {
                throw new RuntimeException("Invalid client credentials during token refresh: " + e.getMessage());
            } else {
                throw new RuntimeException("Token refresh failed: " + e.getMessage());
            }
        } catch (Exception e) {
            // Handle network or other unexpected errors
            if (e.getMessage().contains("network") || e.getMessage().contains("connection") || e.getMessage().contains("timeout")) {
                throw new RuntimeException("Network error during token refresh: " + e.getMessage());
            } else {
                throw new RuntimeException("Unexpected error during token refresh: " + e.getMessage());
            }
        }
    }
    
    private String generateState() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    
    private Environment getEnvironment() {
        return "production".equalsIgnoreCase(config.getEnvironment()) ? 
            Environment.PRODUCTION : Environment.SANDBOX;
    }
    

    private void validateOAuthConfiguration() {
        if (config.getClientId() == null || config.getClientId().trim().isEmpty()) {
            throw new RuntimeException("Client ID is required but not configured");
        }
        
        if (config.getClientSecret() == null || config.getClientSecret().trim().isEmpty()) {
            throw new RuntimeException("Client Secret is required but not configured");
        }
        
        if (config.getDynamicRedirectUri() == null || config.getDynamicRedirectUri().trim().isEmpty()) {
            throw new RuntimeException("Redirect URI is required but not configured");
        }
        
        // Validate redirect URI format
        String redirectUri = config.getDynamicRedirectUri();
        if (!redirectUri.startsWith("http://") && !redirectUri.startsWith("https://")) {
            throw new RuntimeException("Redirect URI must start with http:// or https://");
        }
        
        // Validate environment
        String environment = config.getEnvironment();
        if (environment == null || (!"sandbox".equalsIgnoreCase(environment) && !"production".equalsIgnoreCase(environment))) {
            throw new RuntimeException("Environment must be 'sandbox' or 'production'");
        }
    }
} 
