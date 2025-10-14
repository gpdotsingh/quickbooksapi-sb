package com.quickbooks.demo.controller;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quickbooks.demo.config.QuickBooksConfig;
import com.quickbooks.demo.service.QuickBooksApiService;
import com.quickbooks.demo.service.QuickBooksOAuthService;

@WebMvcTest(controllers = QuickBooksController.class)
class QuickBooksControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QuickBooksOAuthService oauthService;

    @MockBean
    @SuppressWarnings("unused")
    private QuickBooksApiService apiService; // intentionally unused in some tests

    @MockBean
    private QuickBooksConfig config;

    @Test
    void qboLogin_redirectsToAuthUrl() throws Exception {
        when(oauthService.getAuthorizationUrl()).thenReturn("https://example/auth");
        mockMvc.perform(get("/qbo-login"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("https://example/auth"));
    }

    @Test
    void callback_success_setsFlashAuthenticated() throws Exception {
        when(config.getEnvironment()).thenReturn("production");
        Map<String, Object> tokens = new HashMap<>();
        tokens.put("access_token", "access123");
        tokens.put("refresh_token", "refresh123");
        when(oauthService.exchangeCodeForToken(eq("code1"), eq("999"))).thenReturn(tokens);

        mockMvc.perform(get("/callback").param("code", "code1").param("realmId", "999"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attributeExists("success"))
            .andExpect(flash().attribute("authenticated", true));
    }

    @Test
    void callQbo_missingSessionTokens_redirectsWithError() throws Exception {
        mockMvc.perform(get("/call-qbo"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attributeExists("error"));
    }

    @Test
    void refreshToken_missingToken_setsError() throws Exception {
        mockMvc.perform(post("/refresh-token"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attributeExists("error"));
    }

    @Test
    void refreshToken_success_setsSuccessFlash() throws Exception {
        Map<String, Object> resp = new HashMap<>();
        resp.put("access_token", "abc123xyz789");
        when(oauthService.refreshToken("r1")).thenReturn(resp);

        mockMvc.perform(post("/refresh-token").sessionAttr("refreshToken", "r1"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attributeExists("success"));
    }
}


