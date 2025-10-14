package com.quickbooks.demo.service;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mockito;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quickbooks.demo.config.QuickBooksConfig;

public class QuickBooksApiServiceTest {

    private QuickBooksApiService service;
    private QuickBooksConfig config;
    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;

    @BeforeEach
    @SuppressWarnings("unused")
    void setup() {
        service = new QuickBooksApiService();
        config = new QuickBooksConfig();
        config.setBaseUrl("https://quickbooks.api.intuit.com");
        config.setGraphqlUrl("https://qb.api.intuit.com/graphql");
        config.setMinorVersion("75");
        ReflectionTestUtils.setField(service, "config", config);

        restTemplate = Mockito.mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
    }

    @Test
    void getCustomers_parsesBasicResponse() {
        String body = """
                {
                  "QueryResponse": {
                    "Customer": [
                      { "Id": "1", "DisplayName": "Acme" },
                      { "Id": "2", "DisplayName": "Globex" }
                    ]
                  }
                }
                """;
        when(restTemplate.exchange(
                Mockito.anyString(),
                Mockito.any(),
                Mockito.<HttpEntity<String>>any(),
                eq(String.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        Map<String, Object> result = service.getCustomers("Bearer token", "12345");
        assertNotNull(result);
        assertEquals(2, ((java.util.List<?>) result.get("customers")).size());
        assertEquals(2, ((java.util.Map<?, ?>) result.get("customerMap")).size());
    }

    @Test
    void createProject_addsBearerAndParsesResponse() {
        String graphqlResponse = """
                {
                  "data": {
                    "projectManagementCreateProject": {
                      "id": "p-1",
                      "name": "Test Project",
                      "description": "Desc",
                      "status": "ACTIVE",
                      "startDate": "2024-01-01T00:00:00.000Z",
                      "dueDate": "2025-01-01T00:00:00.000Z"
                    }
                  }
                }
                """;

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq(config.getGraphqlUrl()), eq(org.springframework.http.HttpMethod.POST), captor.capture(), eq(String.class)))
            .thenReturn(new ResponseEntity<>(graphqlResponse, HttpStatus.OK));

        Map<String, Object> out = service.createProject("Bearer abc123", "Customer A", "10", null);
        assertEquals("p-1", out.get("id"));
        assertEquals("Test Project", out.get("name"));

        HttpEntity<?> sent = captor.getValue();
        HttpHeaders headers = sent.getHeaders();
        String auth = headers.getFirst(HttpHeaders.AUTHORIZATION);
        assertNotNull(auth);
        assertEquals("Bearer abc123", auth);
    }
}


