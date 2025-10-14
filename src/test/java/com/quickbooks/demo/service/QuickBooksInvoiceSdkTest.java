package com.quickbooks.demo.service;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.intuit.ipp.data.Invoice;
import com.intuit.ipp.services.DataService;

public class QuickBooksInvoiceSdkTest {

    @Test
    void createInvoice_usesSdkAndReturnsFields() throws Exception {
        // Arrange: spy service and stub DataService creation
        DataService dataServiceMock = mock(DataService.class);
        QuickBooksApiService svc = new QuickBooksApiService() {
            @Override
            protected DataService createDataService(com.intuit.ipp.core.Context context) {
                return dataServiceMock;
            }
        };

        // Inject config to support deep link formatting
        com.quickbooks.demo.config.QuickBooksConfig cfg = new com.quickbooks.demo.config.QuickBooksConfig();
        cfg.setDeepLinkTemplate("https://app.qbo.intuit.com/app/invoice?txnId=%s&companyId=%s");
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "config", cfg);

        // Stub SDK add
        Invoice created = new Invoice();
        created.setId("INV-1");
        created.setDocNumber("1001");
        created.setTotalAmt(BigDecimal.valueOf(200));
        when(dataServiceMock.add(any(Invoice.class))).thenReturn(created);

        // Act
        Map<String, Object> result = svc.createInvoice(
            "Bearer at", "12345", "10", "55", "ItemName", "P1", 2, 100.0, "desc");

        // Assert
        assertEquals("INV-1", result.get("invoiceId"));
        assertEquals(200.0, (Double) result.get("amount"), 0.001);
        assertEquals("1001", result.get("docNumber"));
        assertNotNull(result.get("deepLink"));
    }
}


