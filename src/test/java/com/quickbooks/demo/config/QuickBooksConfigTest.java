package com.quickbooks.demo.config;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

public class QuickBooksConfigTest {

    @Test
    void gettersAndSetters_workAndPersistValues() {
        QuickBooksConfig cfg = new QuickBooksConfig();

        cfg.setClientId("cid");
        cfg.setClientSecret("secret");
        cfg.setRedirectUri("https://cb");
        cfg.setEnvironment("production");
        cfg.setBaseUrl("https://quickbooks.api.intuit.com");
        cfg.setGraphqlUrl("https://qb.api.intuit.com/graphql");
        cfg.setScopes(Arrays.asList("s1","s2"));
        cfg.setMinorVersion("75");
        cfg.setDeepLinkTemplate("https://app.qbo.intuit.com/app/invoice?txnId=%s&companyId=%s");

        assertEquals("cid", cfg.getClientId());
        assertEquals("secret", cfg.getClientSecret());
        assertEquals("https://cb", cfg.getRedirectUri());
        assertEquals("production", cfg.getEnvironment());
        assertEquals("https://quickbooks.api.intuit.com", cfg.getBaseUrl());
        assertEquals("https://qb.api.intuit.com/graphql", cfg.getGraphqlUrl());
        assertEquals(2, cfg.getScopes().size());
        assertEquals("75", cfg.getMinorVersion());
        assertEquals("https://app.qbo.intuit.com/app/invoice?txnId=123&companyId=456", cfg.getInvoiceDeepLink("123", "456"));
    }

    @Test
    void deepLinkTemplate_missing_throws() {
        QuickBooksConfig cfg = new QuickBooksConfig();
        cfg.setDeepLinkTemplate(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> cfg.getInvoiceDeepLink("1", "2"));
        assertTrue(ex.getMessage().contains("deep-link-template"));
    }

    @Test
    void buildsDeepLinkFromTemplate() {
        QuickBooksConfig cfg = new QuickBooksConfig();
        cfg.setDeepLinkTemplate("https://app.qbo.intuit.com/app/invoice?txnId=%s&companyId=%s");
        String link = cfg.getInvoiceDeepLink("123", "999");
        assertEquals("https://app.qbo.intuit.com/app/invoice?txnId=123&companyId=999", link);
    }

    @Test
    void gettersReturnConfiguredValues() {
        QuickBooksConfig cfg = new QuickBooksConfig();
        cfg.setClientId("id");
        cfg.setClientSecret("secret");
        cfg.setRedirectUri("http://localhost/cb");
        cfg.setEnvironment("production");
        cfg.setBaseUrl("https://quickbooks.api.intuit.com");
        cfg.setGraphqlUrl("https://qb.api.intuit.com/graphql");
        cfg.setMinorVersion("75");
        cfg.setDeepLinkTemplate("https://app.qbo.intuit.com/app/invoice?txnId=%s&companyId=%s");
        assertEquals("id", cfg.getClientId());
        assertEquals("secret", cfg.getClientSecret());
        assertEquals("http://localhost/cb", cfg.getRedirectUri());
        assertEquals("production", cfg.getEnvironment());
        assertEquals("https://quickbooks.api.intuit.com", cfg.getBaseUrl());
        assertEquals("https://qb.api.intuit.com/graphql", cfg.getGraphqlUrl());
        assertEquals("75", cfg.getMinorVersion());
        assertEquals("https://app.qbo.intuit.com/app/invoice?txnId=%s&companyId=%s", cfg.getDeepLinkTemplate());
    }
}


