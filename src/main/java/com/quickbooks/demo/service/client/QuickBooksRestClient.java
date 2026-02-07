package com.quickbooks.demo.service.client;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quickbooks.demo.config.QuickBooksConfig;
import com.quickbooks.demo.model.QuickBooksContext;

/**
 * Thin client for QuickBooks REST v3 calls (query + JSON endpoints).
 * Centralizes URL construction and auth headers.
 */
@Component
public class QuickBooksRestClient {

    @Autowired
    private QuickBooksConfig config;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    public String query(QuickBooksContext ctx, String query) {
        String url = baseCompanyUrl(ctx) + "/query";
        url = appendMinorVersion(url);

        HttpHeaders headers = textHeaders(ctx);
        HttpEntity<String> request = new HttpEntity<>(query, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("QuickBooks query failed: " + response.getStatusCode() + " - " + response.getBody());
        }
        return response.getBody();
    }

    public String postJson(QuickBooksContext ctx, String path, Object payload) {
        String url = baseCompanyUrl(ctx) + path;
        url = appendMinorVersion(url);

        HttpHeaders headers = jsonHeaders(ctx);
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize payload: " + e.getMessage(), e);
        }

        try {
            HttpEntity<String> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("QuickBooks POST failed: " + response.getStatusCode() + " - " + response.getBody());
            }
            return response.getBody();
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("QuickBooks POST failed: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        }
    }

    private String baseCompanyUrl(QuickBooksContext ctx) {
        return ensureNoTrailingSlash(config.getBaseUrl()) + "/v3/company/" + ctx.realmId();
    }

    private String appendMinorVersion(String url) {
        if (config.getMinorVersion() != null && !config.getMinorVersion().trim().isEmpty()) {
            return url + (url.contains("?") ? "&" : "?") + "minorversion=" + config.getMinorVersion().trim();
        }
        return url;
    }

    private HttpHeaders jsonHeaders(QuickBooksContext ctx) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", ctx.bearerValue());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private HttpHeaders textHeaders(QuickBooksContext ctx) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", ctx.bearerValue());
        // QBO query endpoint expects `application/text` (strict in some environments)
        headers.set("Content-Type", "application/text");
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private String ensureNoTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
