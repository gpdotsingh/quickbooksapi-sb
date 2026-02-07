package com.quickbooks.demo.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quickbooks.demo.service.QuickBooksApiService;

import jakarta.servlet.http.HttpSession;

/**
 * REST endpoint to fetch Accounts from QuickBooks using the existing OAuth token in session.
 */
@RestController
@RequestMapping("/api")
public class AccountsController {

    @Autowired
    private QuickBooksApiService apiService;

    @GetMapping("/accounts")
    public ResponseEntity<?> listAccounts(HttpSession session) {
        String accessToken = (String) session.getAttribute("accessToken");
        String realmId = (String) session.getAttribute("realmId");

        if (accessToken == null || realmId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Please connect to QuickBooks first."));
        }

        try {
            Map<String, Object> result = apiService.getAccounts(accessToken, realmId);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
