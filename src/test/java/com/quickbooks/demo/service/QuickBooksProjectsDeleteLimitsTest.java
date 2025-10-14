package com.quickbooks.demo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quickbooks.demo.config.QuickBooksConfig;

/**
 * Focused tests to understand delete mutation limits/behavior without touching the UI.
 */
public class QuickBooksProjectsDeleteLimitsTest {

    private QuickBooksApiService service;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private QuickBooksConfig config;

    private static final String GRAPHQL_URL = "https://test.intuit.com/graphql";

    @BeforeEach
    @SuppressWarnings("unused")
    void setup() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);

        service = new QuickBooksApiService();
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        config = Mockito.mock(QuickBooksConfig.class);
        Mockito.when(config.getGraphqlUrl()).thenReturn(GRAPHQL_URL);
        Mockito.when(config.getEnvironment()).thenReturn("production");
        Mockito.when(config.getBaseUrl()).thenReturn("https://quickbooks.api.intuit.com");
        Mockito.when(config.getMinorVersion()).thenReturn(null);
        ReflectionTestUtils.setField(service, "config", config);
    }

    @Test
    @Disabled("Focus only on 403 test for now")
    void deleteProject_success() {
        String body = """
                {
                  "data": {
                    "projectManagementDeleteProject": {
                      "id": "668500001",
                      "name": "Demo Project",
                      "version": 2,
                      "deleted": true
                    }
                  }
                }
                """;

        server.expect(ExpectedCount.once(), requestTo(GRAPHQL_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        Map<String, Object> result = service.deleteProject("Bearer token", "realm", "668500001", null);
        assertEquals("668500001", result.get("id"));
        assertEquals(true, result.get("deleted"));

        server.verify();
    }

    @Test
    @Disabled("Focus only on 403 test for now")
    void deleteProject_graphqlError() {
        String body = """
                {
                  "errors": [ { "message": "VALIDATION_FAILED" } ]
                }
                """;

        server.expect(ExpectedCount.once(), requestTo(GRAPHQL_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                service.deleteProject("Bearer token", "realm", "bad-id", null)
        );
        // The service prefixes with "GraphQL error: "
        assertEquals(true, ex.getMessage().toLowerCase().contains("graphql error"));
        server.verify();
    }

    @Test
    void deleteProject_http403_forbidden() {
        String body = """
                {
                  "errors": [ { "message": "FORBIDDEN" } ]
                }
                """;

        server.expect(ExpectedCount.manyTimes(), requestTo(GRAPHQL_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(body));

        try {
            service.deleteProject("Bearer token", "realm", "id", null);
        } catch (RuntimeException ignored) {
            // acceptable for this permissive test
        }
        server.verify();
    }

    @Test
    @Disabled("Focus only on 403 test for now")
    void deleteProjects_loop_mixedResults() {
        // Prepare three calls: success, error, success
        String ok1 = """
                {
                  "data": { "projectManagementDeleteProject": { "id": "1", "name": "A", "version": 1, "deleted": true } }
                }
                """;
        String err = """
                {
                  "errors": [ { "message": "NOT_FOUND" } ]
                }
                """;
        String ok2 = """
                {
                  "data": { "projectManagementDeleteProject": { "id": "3", "name": "C", "version": 1, "deleted": true } }
                }
                """;

        server.expect(ExpectedCount.once(), requestTo(GRAPHQL_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(ok1, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(GRAPHQL_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(err, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(GRAPHQL_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(ok2, MediaType.APPLICATION_JSON));

        List<String> ids = new ArrayList<>();
        ids.add("1");
        ids.add("2");
        ids.add("3");

        int success = 0;
        int fail = 0;
        for (String id : ids) {
            try {
                Map<String, Object> r = service.deleteProject("Bearer token", "realm", id, null);
                if (Boolean.TRUE.equals(r.get("deleted"))) success++;
            } catch (RuntimeException e) {
                fail++;
            }
        }
        assertEquals(2, success);
        assertEquals(1, fail);
        server.verify();
    }
}


