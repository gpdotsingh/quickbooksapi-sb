package com.quickbooks.demo.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;

import com.quickbooks.demo.config.QuickBooksConfig;

class QuickBooksApiServiceValidationTest {

    @Mock
    private QuickBooksConfig config;

    @InjectMocks
    private QuickBooksApiService service;

    @BeforeEach
    @SuppressWarnings("unused")
    void initMocks() {
        MockitoAnnotations.openMocks(this);
        when(config.getBaseUrl()).thenReturn("https://quickbooks.api.intuit.com");
    }

    @Test
    void getItems_throwsWhenMissingAccessToken() {
        Throwable ex = assertThrows(RuntimeException.class, () -> service.getItems(null, "123"));
        assertNotNull(ex);
    }

    @Test
    void getItems_throwsWhenMissingRealmId() {
        Throwable ex = assertThrows(RuntimeException.class, () -> service.getItems("abc", null));
        assertNotNull(ex);
    }

    @Test
    void createInvoice_throwsWhenMissingAccessToken() {
        Throwable ex = assertThrows(RuntimeException.class, () -> service.createInvoice(null, "r", "c", "i", "n", "p", 1, 1.0, null));
        assertNotNull(ex);
    }

    @Test
    void createInvoice_throwsWhenMissingRealm() {
        Throwable ex = assertThrows(RuntimeException.class, () -> service.createInvoice("a", null, "c", "i", "n", "p", 1, 1.0, null));
        assertNotNull(ex);
    }

    @Test
    void createInvoice_throwsWhenMissingCustomer() {
        Throwable ex = assertThrows(RuntimeException.class, () -> service.createInvoice("a", "r", null, "i", "n", "p", 1, 1.0, null));
        assertNotNull(ex);
    }

    @Test
    void createInvoice_throwsWhenMissingItem() {
        Throwable ex = assertThrows(RuntimeException.class, () -> service.createInvoice("a", "r", "c", null, "n", "p", 1, 1.0, null));
        assertNotNull(ex);
    }

    @Test
    void createInvoice_throwsWhenMissingProject() {
        Throwable ex = assertThrows(RuntimeException.class, () -> service.createInvoice("a", "r", "c", "i", "n", null, 1, 1.0, null));
        assertNotNull(ex);
    }

    @Test
    void generateInvoiceDeepLink_throwsOnMissingIds() {
        Throwable ex1 = assertThrows(RuntimeException.class, () -> service.generateInvoiceDeepLink(null, "r"));
        Throwable ex2 = assertThrows(RuntimeException.class, () -> service.generateInvoiceDeepLink("i", null));
        assertNotNull(ex1);
        assertNotNull(ex2);
    }
}


