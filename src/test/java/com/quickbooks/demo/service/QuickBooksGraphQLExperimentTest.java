package com.quickbooks.demo.service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quickbooks.demo.config.QuickBooksConfig;

/**
 * GraphQL experiment harness: easily plug query/mutation and variables and inspect behavior.
 */
public class QuickBooksGraphQLExperimentTest {

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

    private static String readClasspath(String path) throws Exception {
        ClassPathResource r = new ClassPathResource(path);
        return new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void getProjectById_success() throws Exception {
        // Arrange: load query text and craft variables
        String query = readClasspath("graphql/project_get.graphql");
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", "668494482");

        // Expectation: request contains query and variables, return success
        String body = """
                {
                  "data": {
                    "projectManagementProject": {
                      "id": "668494482",
                      "name": "Project-5959e9ef-24ee-49f1-a39f-7203cb20b762",
                      "status": "OPEN",
                      "description": "Demo",
                      "startDate": "2025-09-24T23:28:12.115Z",
                      "dueDate": "2030-09-24T23:28:12.115Z",
                      "account": { "id": "9341455322581846" },
                      "customer": { "id": "12" }
                    }
                  }
                }
                """;

        server.expect(ExpectedCount.once(), requestTo(GRAPHQL_URL))
              .andExpect(method(HttpMethod.POST))
              .andExpect(jsonPath("$.query").value(query))
              .andExpect(jsonPath("$.variables.id").value("668494482"))
              .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // Act
        Map<String, Object> p = service.getProjectById("Bearer token", "realm", "668494482");

        // Assert
        assertEquals("668494482", p.get("id"));
        assertEquals("OPEN", p.get("status"));
        assertNotNull(p.get("accountId"));

        server.verify();
    }

    @Test
    void getProjectById_graphqlError() throws Exception {
        String query = readClasspath("graphql/project_get.graphql");
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", "bad");

        String body = """
                {
                  "errors": [ { "message": "Project not found" } ]
                }
                """;

        server.expect(ExpectedCount.once(), requestTo(GRAPHQL_URL))
              .andExpect(method(HttpMethod.POST))
              .andExpect(jsonPath("$.query").value(query))
              .andExpect(jsonPath("$.variables.id").value("bad"))
              .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        try {
            service.getProjectById("Bearer token", "realm", "bad");
        } catch (RuntimeException e) {
            assertEquals(true, e.getMessage().toLowerCase().contains("graphql error"));
        }

        server.verify();
    }
}


