package com.quickbooks.demo.config;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;


@Configuration
@ConfigurationProperties(prefix = "quickbooks")
public class QuickBooksConfig {
    
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String environment = "sandbox";
    private String baseUrl = "https://quickbooks.api.intuit.com";
    private String graphqlUrl = "https://qb.api.intuit.com/graphql";
    private List<String> scopes = new ArrayList<>();
    private String minorVersion = "75";
    private String deepLinkTemplate = "https://app.qbo.intuit.com/app/invoice?txnId=%s&companyId=%s";
    
    public QuickBooksConfig() {
       
    }
    
    // Getters and setters
    public String getClientId() {
        return clientId;
    }
    
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
    
    public String getClientSecret() {
        return clientSecret;
    }
    
    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }
    
    public String getRedirectUri() {
        return redirectUri;
    }
    
    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }
    
    public String getEnvironment() {
        return environment;
    }
    
    public void setEnvironment(String environment) {
        this.environment = environment;
    }
    
    public List<String> getScopes() {
        return scopes;
    }
    
    public void setScopes(List<String> scopes) {
        this.scopes = scopes;
    }
    
    public String getBaseUrl() {
        return baseUrl;
    }
    
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getGraphqlUrl() {
        return graphqlUrl;
    }
    
    public void setGraphqlUrl(String graphqlUrl) {
        this.graphqlUrl = graphqlUrl;
    }
    
    public String getInvoiceDeepLink(String invoiceId, String realmId) {
        String template = deepLinkTemplate;
        if (template == null || template.trim().isEmpty()) {
            throw new IllegalStateException("quickbooks.deep-link-template is required to build invoice deep link");
        }
        return String.format(template, invoiceId, realmId);
    }
    
    // OAuth URLs are handled automatically by the SDK's discovery API
    
    /**
     * Redirect URI configured for the OAuth callback.
     */
    public String getDynamicRedirectUri() {
        return redirectUri;
    }
    
    public String getMinorVersion() {
        return minorVersion;
    }
    
    public void setMinorVersion(String minorVersion) {
        this.minorVersion = minorVersion;
    }

    public String getDeepLinkTemplate() {
        return deepLinkTemplate;
    }

    public void setDeepLinkTemplate(String deepLinkTemplate) {
        this.deepLinkTemplate = deepLinkTemplate;
    }

    /**
     * Shared HTTP client for REST/GraphQL calls.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * Shared JSON mapper for services.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
} 