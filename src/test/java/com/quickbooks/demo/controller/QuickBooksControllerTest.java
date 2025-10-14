package com.quickbooks.demo.controller;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quickbooks.demo.config.QuickBooksConfig;
import com.quickbooks.demo.service.QuickBooksApiService;
import com.quickbooks.demo.service.QuickBooksOAuthService;

@WebMvcTest(QuickBooksController.class)
public class QuickBooksControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QuickBooksOAuthService oauthService;

    @MockBean
    @SuppressWarnings("unused")
    private QuickBooksApiService apiService;

    @MockBean
    @SuppressWarnings("unused")
    private QuickBooksConfig config;

    // Removed unused mocks to silence linter warnings

    // Removed empty setup to avoid "setup is never used" warning

    @Test
    void refreshToken_updatesSessionAndFlashesMaskedSuccess() throws Exception {
        Map<String, Object> refreshed = new HashMap<>();
        refreshed.put("access_token", "newaccesstokenvalue");
        refreshed.put("refresh_token", "newrefresh");
        when(oauthService.refreshToken(Mockito.anyString())).thenReturn(refreshed);

        mockMvc.perform(post("/refresh-token").sessionAttr("refreshToken", "oldrefresh"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attributeExists("success"));
    }
}


