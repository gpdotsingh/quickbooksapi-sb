package com.quickbooks.demo.controller;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.quickbooks.demo.config.QuickBooksConfig;
import com.quickbooks.demo.service.QuickBooksApiService;
import com.quickbooks.demo.service.QuickBooksOAuthService;

import jakarta.servlet.http.HttpSession;

@Controller
public class QuickBooksController {
    
    @Autowired
    private QuickBooksOAuthService oauthService;
    
    @Autowired
    private QuickBooksApiService apiService;
    
    @Autowired
    private QuickBooksConfig config;
    
    /**
     * Home page
     */
    @GetMapping("/")
    public String home(Model model, HttpSession session) {
        // DEBUG: Show what's currently in session
        String accessToken = (String) session.getAttribute("accessToken");
        String realmId = (String) session.getAttribute("realmId");
        Boolean authCompleted = (Boolean) session.getAttribute("authCompleted");
        
      
        boolean isAuthenticated = ((accessToken != null && !accessToken.trim().isEmpty()) && 
                                  (realmId != null && !realmId.trim().isEmpty())) ||
                                  (authCompleted != null && authCompleted);
        
        model.addAttribute("authenticated", isAuthenticated);
        // Feature flag: disable write forms on sandbox
        model.addAttribute("allowWrites", !"sandbox".equalsIgnoreCase(config.getEnvironment()));
        
        // Clear any lingering invoice data if not authenticated
        if (!isAuthenticated) {
            session.removeAttribute("invoiceId");
            session.removeAttribute("invoiceDeepLink");
            session.removeAttribute("invoiceProjectId");
            session.removeAttribute("invoiceAmount");
            session.removeAttribute("invoiceNumber");
            session.removeAttribute("project");
            session.removeAttribute("customer_map");
            session.removeAttribute("items");
            session.removeAttribute("itemNames");
            session.removeAttribute("itemMap");
        }
        
        // Pass session data to template to maintain UI state
        @SuppressWarnings("unchecked")
        Map<String, String> customerMap = (Map<String, String>) session.getAttribute("customer_map");
        if (customerMap != null) {
            // Convert customer map to list of names for dropdown
            model.addAttribute("customers", customerMap.values());
        }
        
        // Pass project data if it exists
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) session.getAttribute("project");
        if (project != null) {
            model.addAttribute("project", project);
        }
        
        return "index";
    }
    
    /**
     * Initiate OAuth login
     */
    @GetMapping("/qbo-login")
    public String initiateOAuth(RedirectAttributes redirectAttributes) {
        try {
            String authUrl = oauthService.getAuthorizationUrl();
            return "redirect:" + authUrl;
        } catch (RuntimeException e) {
            // Surface specific OAuth errors (e.g., invalid_client) to the UI
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to start authentication. Please try again.");
            return "redirect:/";
        }
    }

    /**
     * Create bill linked to project via Accounting REST API
     */
    @PostMapping("/create-bill")
    public String createBill(
            @RequestParam String vendorId,
            @RequestParam String expenseAccountId,
            @RequestParam String projectId,
            @RequestParam double amount,
            @RequestParam(required = false) String description,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        try {
            String accessToken = (String) session.getAttribute("accessToken");
            String realmId = (String) session.getAttribute("realmId");
            if (accessToken == null || realmId == null) {
                redirectAttributes.addFlashAttribute("error", "Please connect to QuickBooks first.");
                return "redirect:/";
            }

            Map<String, Object> billResult = apiService.createBill(
                accessToken, realmId, vendorId, expenseAccountId, projectId, amount, description
            );

            session.setAttribute("billId", billResult.get("billId"));
            session.setAttribute("billProjectId", billResult.get("projectId"));
            session.setAttribute("billAmount", billResult.get("totalAmt"));
            session.setAttribute("billDeepLink", billResult.get("deepLink"));

            // Hint UI to focus Step 8 success panel
            redirectAttributes.addFlashAttribute("focusTarget", "bill-success");

            redirectAttributes.addFlashAttribute("success",
                "✅ Bill created successfully! Bill ID: " + billResult.get("billId") +
                ", linked to Project ID: " + billResult.get("projectId"));
            return "redirect:/";
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", "Failed to create bill: " + e.getMessage());
            return "redirect:/";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Unexpected error creating bill. Please try again.");
            return "redirect:/";
        }
    }
    
    /**
     * OAuth callback handler using SDK
     */
    @GetMapping("/callback")
    public String handleCallback(
            @RequestParam("code") String authCode,
            @RequestParam("realmId") String realmId,
            @RequestParam(value = "state", required = false) String state,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        
        // Check if we already processed this callback to prevent double processing
        String lastProcessedCode = (String) session.getAttribute("lastProcessedAuthCode");
        if (authCode.equals(lastProcessedCode)) {
            redirectAttributes.addFlashAttribute("success", "Already connected to QuickBooks!");
            return "redirect:/";
        }
        
        try {
            // Mark this code as being processed
            session.setAttribute("lastProcessedAuthCode", authCode);
            
            // OAuth callback received; SDK handles environment validation
            
            // Note: Let the SDK handle environment validation automatically
            // The OAuth token will only work with companies from the same environment
            
            // Exchange code for token using SDK
            Map<String, Object> tokenData = oauthService.exchangeCodeForToken(authCode, realmId);
            
            // Store tokens in session (format Bearer token for API calls)
            String accessToken = (String) tokenData.get("access_token");
            String authHeader = "Bearer " + accessToken;
            
            // Store tokens in session with explicit session management
            session.setAttribute("accessToken", authHeader);
            session.setAttribute("refreshToken", tokenData.get("refresh_token"));
            session.setAttribute("realmId", realmId);
            session.setAttribute("authenticated", true);
            if (tokenData.get("scope") != null) {
                session.setAttribute("grantedScope", tokenData.get("scope"));
            }
            
            // Add timestamp for debugging
            session.setAttribute("authTimestamp", System.currentTimeMillis());
            
            // Ensure session is immediately available by invalidating old session data
            session.removeAttribute("SPRING_SECURITY_CONTEXT"); // Clear any security context
            
            // Force session to be created and persisted immediately
            session.setMaxInactiveInterval(3600); // 1 hour
            
            // Clear any old invoice data when reconnecting
            session.removeAttribute("invoiceId");
            session.removeAttribute("invoiceDeepLink");
            session.removeAttribute("invoiceProjectId");
            session.removeAttribute("invoiceAmount");
            session.removeAttribute("invoiceNumber");
            
            redirectAttributes.addFlashAttribute("success", "Successfully connected to QuickBooks! You can now fetch customers.");
            
            // ✅ THE FIX IS HERE: Add the authenticated status as a flash attribute to survive the redirect
            redirectAttributes.addFlashAttribute("authenticated", true);
            
            // Force session to be immediately available
            session.setAttribute("authCompleted", true);
            
        } catch (RuntimeException e) {
            // Handle specific OAuth errors
            String errorMsg = e.getMessage().toLowerCase();
            if (errorMsg.contains("invalid_grant") || errorMsg.contains("invalid authorization code")) {
                redirectAttributes.addFlashAttribute("error", "Authorization code expired or already used. Please try connecting again.");
                // Clear the processed code to allow retry
                session.removeAttribute("lastProcessedAuthCode");
            } else {
                redirectAttributes.addFlashAttribute("error", e.getMessage());
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Authentication failed. Please try again.");
        }
        
        return "redirect:/";
    }
    
 
    @GetMapping("/call-qbo")
    public String fetchCustomers(Model model, HttpSession session, RedirectAttributes redirectAttributes) {
        try {
            String accessToken = (String) session.getAttribute("accessToken");
            String realmId = (String) session.getAttribute("realmId");
            
            // Validate session tokens
            if (accessToken == null || realmId == null) {
                redirectAttributes.addFlashAttribute("error", "Please authenticate with QuickBooks first - session missing tokens");
                return "redirect:/";
            }
            
            // Get customers using OAuth2PlatformClient + RestTemplate
            Map<String, Object> customerData = apiService.getCustomers(accessToken, realmId);
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> customers = (List<Map<String, Object>>) customerData.get("customers");
            
            @SuppressWarnings("unchecked")
            Map<String, String> customerMap = (Map<String, String>) customerData.get("customerMap");
            
            // Store customers for project creation
            session.setAttribute("customer_map", customerMap);
            
            Map<String, Object> itemsResult = apiService.getItems(accessToken, realmId);
            session.setAttribute("items", itemsResult.get("items"));
            session.setAttribute("itemNames", itemsResult.get("itemNames"));
            session.setAttribute("itemMap", itemsResult.get("itemMap"));

            // Preload vendors and expense accounts for Step 8 dropdowns
            try {
                Map<String, Object> vendorsResult = apiService.getVendors(accessToken, realmId);
                Map<String, Object> accountsResult = apiService.getExpenseAccounts(accessToken, realmId);
                session.setAttribute("vendors", vendorsResult.get("vendors"));
                session.setAttribute("expenseAccounts", accountsResult.get("accounts"));
            } catch (RuntimeException ignored) {
                // Keep page functional even if these lookups fail
            }
            
            // Clear the temporary auth completion flag since authentication is now fully working
            session.removeAttribute("authCompleted");
            
            model.addAttribute("authenticated", true);
            redirectAttributes.addFlashAttribute("success", "✅ Successfully loaded " + customers.size() + " customers and " + 
                ((List<?>) itemsResult.get("items")).size() + " items! Ready for project creation.");
            
            return "redirect:/";
            
        } catch (RuntimeException e) {
            // Handle API-specific errors with user-friendly messages
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        } catch (Exception e) {
            // Handle unexpected errors
            redirectAttributes.addFlashAttribute("error", "Unable to fetch customers. Please try again.");
            return "redirect:/";
        }
    }
    
    /**
     * Create project using GraphQL API
     */
    @PostMapping("/create-project")
    public String createProject(
            @RequestParam("customerName") String customerName,
            @RequestParam(value = "projectName", required = false) String projectName,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        
        try {
            // Look up real customer ID from session customer map
            @SuppressWarnings("unchecked")
            Map<String, String> customerMap = (Map<String, String>) session.getAttribute("customer_map");
            
            String realCustomerId = null;
            if (customerMap != null) {
                // Find customer ID by name (reverse lookup)
                for (Map.Entry<String, String> entry : customerMap.entrySet()) {
                    if (entry.getValue().equals(customerName)) {
                        realCustomerId = entry.getKey();
                        break;
                    }
                }
            }
            
            String accessToken = (String) session.getAttribute("accessToken");
            String realmId = (String) session.getAttribute("realmId");
            
            if (accessToken == null || realmId == null) {
                redirectAttributes.addFlashAttribute("error", "Please authenticate with QuickBooks first");
                return "redirect:/";
            }
            
            if (realCustomerId == null) {
                redirectAttributes.addFlashAttribute("error", "Could not find customer ID for: " + customerName + ". Please fetch customers first.");
                return "redirect:/";
            }
            
            // Create project using GraphQL API
            Map<String, Object> projectData = apiService.createProject(accessToken, customerName, realCustomerId, projectName);
            
            // Store project in session to maintain state across redirects
            session.setAttribute("project", projectData);
            session.setAttribute("projectSource", "created");

            // Clear any previous invoice state when a new project is created
            session.removeAttribute("invoiceId");
            session.removeAttribute("invoiceDeepLink");
            session.removeAttribute("invoiceProjectId");
            session.removeAttribute("invoiceAmount");
            session.removeAttribute("invoiceNumber");
            
            redirectAttributes.addFlashAttribute("success", "Project created successfully!");
            
            return "redirect:/";
            
        } catch (RuntimeException e) {
            // Handle API-specific errors with user-friendly messages
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        } catch (Exception e) {
            // Handle unexpected errors
            redirectAttributes.addFlashAttribute("error", "Unable to create project. Please try again.");
            return "redirect:/";
        }
    }

    /**
     * Delete project (GraphQL) by id (and optional version).
     * Adds a success panel below Step 3 and focuses it.
     */
    @PostMapping("/delete-project")
    public String deleteProject(@RequestParam("id") String id,
                                @RequestParam(value = "version", required = false) Integer version,
                                HttpSession session,
                                RedirectAttributes redirectAttributes) {
        try {
            String accessToken = (String) session.getAttribute("accessToken");
            String realmId = (String) session.getAttribute("realmId");
            if (accessToken == null || realmId == null) {
                redirectAttributes.addFlashAttribute("error", "Please authenticate with QuickBooks first");
                return "redirect:/";
            }

            java.util.Map<String, Object> result = apiService.deleteProject(accessToken, realmId, id, version);
            session.setAttribute("projectDeleteResult", result);
            // Clear project in session if deleted project matches
            Object current = session.getAttribute("project");
            if (current instanceof java.util.Map<?, ?> m) {
                Object cid = m.get("id");
                if (cid != null && id.equals(cid.toString())) {
                    session.removeAttribute("project");
                }
            }
            redirectAttributes.addFlashAttribute("focusTarget", "project-delete-success");
            redirectAttributes.addFlashAttribute("success", "✅ Project deleted: " + id);
            return "redirect:/";
        } catch (RuntimeException e) {
            session.setAttribute("projectDeleteError", e.getMessage());
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        } catch (Exception e) {
            session.setAttribute("projectDeleteError", "Unable to delete project.");
            redirectAttributes.addFlashAttribute("error", "Unable to delete project.");
            return "redirect:/";
        }
    }

    /**
     * Delete multiple projects by comma-separated ids. Returns per-id results in session.
     */
    @PostMapping("/delete-projects-multi")
    public String deleteProjectsMulti(@RequestParam("ids") String idsCsv,
                                      @RequestParam(value = "version", required = false) Integer version,
                                      HttpSession session,
                                      RedirectAttributes redirectAttributes) {
        String accessToken = (String) session.getAttribute("accessToken");
        String realmId = (String) session.getAttribute("realmId");
        if (accessToken == null || realmId == null) {
            redirectAttributes.addFlashAttribute("error", "Please authenticate with QuickBooks first");
            return "redirect:/";
        }
        java.util.List<java.util.Map<String, Object>> results = new java.util.ArrayList<>();
        int successCount = 0;
        int failCount = 0;
        for (String raw : idsCsv.split(",")) {
            String id = raw.trim();
            if (id.isEmpty()) continue;
            java.util.Map<String, Object> row = new java.util.HashMap<>();
            row.put("id", id);
            try {
                java.util.Map<String, Object> del = apiService.deleteProject(accessToken, realmId, id, version);
                row.put("status", "success");
                row.put("name", del.get("name"));
                row.put("deleted", del.get("deleted"));
                successCount++;
            } catch (RuntimeException ex) {
                row.put("status", "error");
                row.put("error", ex.getMessage());
                failCount++;
            }
            results.add(row);
        }
        session.setAttribute("projectDeleteMultiResults", results);
        redirectAttributes.addFlashAttribute("focusTarget", "project-delete-multi-results");
        redirectAttributes.addFlashAttribute("success", "Delete complete: " + successCount + " success, " + failCount + " failed.");
        return "redirect:/";
    }
    
    /**
     * Logout - Clear all session data
     */
    @GetMapping("/logout")
    public String logout(HttpSession session, RedirectAttributes redirectAttributes) {
        // Best-effort revoke tokens to force a clean reconnect next time
        try {
            String accessHeader = (String) session.getAttribute("accessToken");
            String refreshToken = (String) session.getAttribute("refreshToken");
            String rawAccess = (accessHeader != null && accessHeader.startsWith("Bearer ")) ? accessHeader.substring(7) : accessHeader;
            if (rawAccess != null) { oauthService.revokeTokens(rawAccess); }
            if (refreshToken != null) { oauthService.revokeTokens(refreshToken); }
        } catch (Exception ignore) {}

        // Clear all session attributes individually first
        session.removeAttribute("accessToken");
        session.removeAttribute("refreshToken");
        session.removeAttribute("realmId");
        session.removeAttribute("authenticated");
        session.removeAttribute("authCompleted");
        session.removeAttribute("authTimestamp");
        session.removeAttribute("lastProcessedAuthCode");
        session.removeAttribute("customer_map");
        session.removeAttribute("items");
        session.removeAttribute("itemNames");
        session.removeAttribute("itemMap");
        session.removeAttribute("project");
        session.removeAttribute("invoiceId");
        session.removeAttribute("invoiceDeepLink");
        session.removeAttribute("invoiceProjectId");
        session.removeAttribute("invoiceAmount");
        session.removeAttribute("invoiceNumber");
        
        // Then invalidate the entire session
        session.invalidate();
        redirectAttributes.addFlashAttribute("success", "Successfully disconnected. Please reconnect; a company picker and login will be shown.");
        return "redirect:/";
    }
    
    /**
     * Create invoice linked to project using QuickBooks Java SDK
     * Uses the proper QuickBooks Java SDK for type-safe invoice creation
     */
    @PostMapping("/create-invoice")
    public String createInvoice(
            @RequestParam String customerId,
            @RequestParam String itemId, 
            @RequestParam String itemName,
            @RequestParam String projectId,
            @RequestParam int quantity,
            @RequestParam double amount,
            @RequestParam(required = false) String description,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        
        try {
            // Get stored tokens from session
            String accessToken = (String) session.getAttribute("accessToken");
            String realmId = (String) session.getAttribute("realmId");
            
            if (accessToken == null || realmId == null) {
                redirectAttributes.addFlashAttribute("error", "Please connect to QuickBooks first.");
                return "redirect:/";
            }
            
            // Create invoice using SDK with user-selected customerId and provided projectId
            Map<String, Object> invoiceResult = apiService.createInvoice(
                accessToken, realmId, customerId, itemId, itemName,
                projectId, quantity, amount, description
            );
            
            // Store invoice details in session for display
            session.setAttribute("invoiceId", invoiceResult.get("invoiceId"));
            session.setAttribute("invoiceDeepLink", invoiceResult.get("deepLink"));
            session.setAttribute("invoiceProjectId", invoiceResult.get("projectId"));
            session.setAttribute("invoiceAmount", invoiceResult.get("amount"));
            session.setAttribute("invoiceNumber", invoiceResult.get("docNumber"));
            
            // Hint UI to focus Step 6 success panel
            redirectAttributes.addFlashAttribute("focusTarget", "invoice-success");

            redirectAttributes.addFlashAttribute("success", 
                "✅ Invoice created successfully using SDK! " + 
                "Invoice #" + invoiceResult.get("docNumber") + 
                " (ID: " + invoiceResult.get("invoiceId") + ") " + 
                "linked to Project ID: " + invoiceResult.get("projectId"));
            
            return "redirect:/";
            
        } catch (RuntimeException e) {
            // Handle API-specific errors with user-friendly messages
            redirectAttributes.addFlashAttribute("error", "SDK Error: " + e.getMessage());
            return "redirect:/";
        } catch (Exception e) {
            // Handle unexpected errors
            redirectAttributes.addFlashAttribute("error", "Failed to create invoice using SDK. Please try again.");
            return "redirect:/";
        }
    }
    
    /**
     * Fetch items for invoice creation
     * Helper endpoint to populate items dropdown
     */
    @GetMapping("/fetch-items")
    public String fetchItems(HttpSession session, RedirectAttributes redirectAttributes) {
        try {
            // Get stored tokens from session
            String accessToken = (String) session.getAttribute("accessToken");
            String realmId = (String) session.getAttribute("realmId");
            
            if (accessToken == null || realmId == null) {
                redirectAttributes.addFlashAttribute("error", "Please connect to QuickBooks first.");
                return "redirect:/";
            }
            
            // Fetch items and store in session
            Map<String, Object> itemsResult = apiService.getItems(accessToken, realmId);
            session.setAttribute("items", itemsResult.get("items"));
            session.setAttribute("itemNames", itemsResult.get("itemNames"));
            session.setAttribute("itemMap", itemsResult.get("itemMap"));
            
            redirectAttributes.addFlashAttribute("success", "Items loaded successfully!");
            
            return "redirect:/";
            
        } catch (RuntimeException e) {
            // Handle API-specific errors with user-friendly messages
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        } catch (Exception e) {
            // Handle unexpected errors
            redirectAttributes.addFlashAttribute("error", "Failed to fetch items. Please try again.");
            return "redirect:/";
        }
    }

    @PostMapping("/create-estimate")
    public String createEstimate(
            @RequestParam String customerId,
            @RequestParam String itemId,
            @RequestParam String projectId,
            @RequestParam int quantity,
            @RequestParam double amount,
            @RequestParam(required = false) String description,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        try {
            String accessToken = (String) session.getAttribute("accessToken");
            String realmId = (String) session.getAttribute("realmId");
            if (accessToken == null || realmId == null) {
                redirectAttributes.addFlashAttribute("error", "Please connect to QuickBooks first.");
                return "redirect:/";
            }
            Map<String, Object> result = apiService.createEstimate(accessToken, realmId, customerId, itemId, projectId, quantity, amount, description);
            // Store details for panel and focus Step 7
            session.setAttribute("estimateId", result.get("estimateId"));
            session.setAttribute("estimateAmount", result.get("totalAmt"));
            session.setAttribute("estimateProjectId", result.get("projectId"));
            redirectAttributes.addFlashAttribute("focusTarget", "estimate-success");
            redirectAttributes.addFlashAttribute("success", "✅ Estimate created for Project ID: " + result.get("projectId"));
            return "redirect:/";
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to create estimate.");
            return "redirect:/";
        }
    }

    @PostMapping("/create-sales-receipt")
    public String createSalesReceipt(
            @RequestParam String customerId,
            @RequestParam String itemId,
            @RequestParam String projectId,
            @RequestParam int quantity,
            @RequestParam double amount,
            @RequestParam(required = false) String description,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        try {
            String accessToken = (String) session.getAttribute("accessToken");
            String realmId = (String) session.getAttribute("realmId");
            if (accessToken == null || realmId == null) {
                redirectAttributes.addFlashAttribute("error", "Please connect to QuickBooks first.");
                return "redirect:/";
            }
            Map<String, Object> result = apiService.createSalesReceipt(accessToken, realmId, customerId, itemId, projectId, quantity, amount, description);
            session.setAttribute("salesReceiptId", result.get("salesReceiptId"));
            session.setAttribute("salesReceiptAmount", result.get("totalAmt"));
            session.setAttribute("salesReceiptProjectId", result.get("projectId"));
            session.setAttribute("salesReceiptDeepLink", result.get("deepLink"));
            redirectAttributes.addFlashAttribute("focusTarget", "sales-receipt-success");
            redirectAttributes.addFlashAttribute("success", "✅ Sales receipt created for Project ID: " + result.get("projectId"));
            return "redirect:/";
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to create sales receipt.");
            return "redirect:/";
        }
    }
    /**
     * List projects (GraphQL) with optional pagination.
     */
    @PostMapping("/projects")
    public String listProjects(@RequestParam(value = "first", required = false) Integer first,
                               @RequestParam(value = "after", required = false) String after,
                               @RequestParam(value = "startDate", required = false) String startDate,
                               @RequestParam(value = "endDate", required = false) String endDate,
                               HttpSession session,
                               RedirectAttributes redirectAttributes) {
        try {
            String accessToken = (String) session.getAttribute("accessToken");
            String realmId = (String) session.getAttribute("realmId");
            if (accessToken == null || realmId == null) {
                redirectAttributes.addFlashAttribute("error", "Please connect to QuickBooks first.");
                return "redirect:/";
            }
            int pageSize = (first == null || first <= 0) ? 10 : first;
            Map<String, Object> result = apiService.listProjects(accessToken, realmId, pageSize, (after != null && !after.isEmpty()) ? after : null, startDate, endDate);
            session.setAttribute("projects", result);
            // Mark where to focus
            session.setAttribute("projectsQuerySuccess", true);
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> nodes = (java.util.List<Map<String, Object>>) result.get("nodes");
            int count = nodes != null ? nodes.size() : 0;
            redirectAttributes.addFlashAttribute("success", "Loaded " + count + " projects.");
            return "redirect:/";
        } catch (RuntimeException e) {
            // Surface error below Step 4 (not only at top) by flagging a message near results area
            session.setAttribute("projects_error", explainProjectsError(e.getMessage()));
            redirectAttributes.addFlashAttribute("message", explainProjectsError(e.getMessage()));
            return "redirect:/";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Unable to list projects. Please try again.");
            return "redirect:/";
        }
    }

    private String explainProjectsError(String raw) {
        if (raw == null) return "An unexpected error occurred.";
        String lower = raw.toLowerCase();
        if (lower.contains("cannot construct instance") && lower.contains("orderby")) {
            return "Projects list error: The 'orderBy' variable format was invalid for this schema. " +
                   "Some environments expect enum strings like DUE_DATE_DESC instead of objects. " +
                   "We now send enum values; retry the request.";
        }
        if (lower.contains("projectmanagementprojects") && lower.contains("exception while fetching data")) {
            return "Projects list error: Backend returned a null fetch. Ensure Projects are enabled in QBO and the token includes scope 'project-management.project'.";
        }
        return raw;
    }

    /**
     * Get a project by ID (GraphQL).
     */
    @PostMapping("/projects/get")
    public String getProject(@RequestParam("id") String id,
                             HttpSession session,
                             RedirectAttributes redirectAttributes) {
        try {
            String accessToken = (String) session.getAttribute("accessToken");
            String realmId = (String) session.getAttribute("realmId");
            if (accessToken == null || realmId == null) {
                redirectAttributes.addFlashAttribute("error", "Please connect to QuickBooks first.");
                return "redirect:/";
            }
            Map<String, Object> project = apiService.getProjectById(accessToken, realmId, id);
            // Resolve the accounting Project (Customer) id to ensure Step 6 uses a valid ProjectRef
            String parentCustomerId = null;
            Object cust = project.get("customer");
            if (cust instanceof java.util.Map<?, ?> m) {
                Object v = m.get("id");
                parentCustomerId = v != null ? v.toString() : null;
            }
            String accountingProjectId = apiService.resolveAccountingProjectId(accessToken, realmId,
                    (String) project.get("name"), parentCustomerId);
            if (accountingProjectId != null) {
                project.put("accountingProjectId", accountingProjectId);
            }
            session.setAttribute("project", project);
            session.setAttribute("projectSource", "read");
            redirectAttributes.addFlashAttribute("success", "Project loaded: " + (project.get("name") != null ? project.get("name") : project.get("id")));
            return "redirect:/";
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Unable to get project. Please try again.");
            return "redirect:/";
        }
    }

    /**
     * Get multiple projects by IDs (comma-separated ids or repeated param ids[])
     */
    @PostMapping("/projects/get-multi")
    public String getProjectsMulti(@RequestParam(value = "ids", required = false) String idsCsv,
                                   @RequestParam(value = "ids[]", required = false) java.util.List<String> idsArray,
                                   HttpSession session,
                                   RedirectAttributes redirectAttributes) {
        try {
            String accessToken = (String) session.getAttribute("accessToken");
            String realmId = (String) session.getAttribute("realmId");
            if (accessToken == null || realmId == null) {
                redirectAttributes.addFlashAttribute("error", "Please connect to QuickBooks first.");
                return "redirect:/";
            }
            java.util.List<String> ids = new java.util.ArrayList<>();
            if (idsArray != null && !idsArray.isEmpty()) {
                ids.addAll(idsArray);
            }
            if (idsCsv != null && !idsCsv.trim().isEmpty()) {
                for (String part : idsCsv.split(",")) {
                    String v = part.trim();
                    if (!v.isEmpty()) ids.add(v);
                }
            }
            if (ids.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Please provide at least one project ID.");
                return "redirect:/";
            }

            java.util.List<java.util.Map<String, Object>> results = apiService.getProjectsByIds(accessToken, realmId, ids);
            session.setAttribute("projects_multi", results);
            redirectAttributes.addFlashAttribute("success", "Loaded " + results.size() + " project(s) by ID.");
            return "redirect:/";
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Unable to get projects. Please try again.");
            return "redirect:/";
        }
    }

    /**
     * Create a new Customer and refresh session customer map
     */
    @PostMapping("/create-customer")
    public String createCustomer(@RequestParam("displayName") String displayName,
                                 @RequestParam(value = "email", required = false) String email,
                                 @RequestParam(value = "phone", required = false) String phone,
                                 HttpSession session,
                                 RedirectAttributes redirectAttributes) {
        try {
            String accessToken = (String) session.getAttribute("accessToken");
            String realmId = (String) session.getAttribute("realmId");

            if (accessToken == null || realmId == null) {
                redirectAttributes.addFlashAttribute("error", "Please connect to QuickBooks first.");
                return "redirect:/";
            }

            Map<String, Object> created = apiService.createCustomer(accessToken, realmId, displayName, email, phone);

            // Refresh customers in session
            Map<String, Object> customerData = apiService.getCustomers(accessToken, realmId);
            session.setAttribute("customer_map", customerData.get("customerMap"));

            redirectAttributes.addFlashAttribute("success", "Customer created: " + created.get("name") + " (ID: " + created.get("id") + ")");
            return "redirect:/";
        } catch (RuntimeException e) {
            String realm = (String) session.getAttribute("realmId");
            String env = config.getEnvironment();
            redirectAttributes.addFlashAttribute("error", e.getMessage() + " [env=" + env + ", realmId=" + (realm != null ? realm : "(none)") + "]");
            return "redirect:/";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to create customer. Please try again.");
            return "redirect:/";
        }
    }

    /**
     * Create a new Item and refresh session item list
     */
    @PostMapping("/create-item")
    public String createItem(@RequestParam("name") String name,
                             @RequestParam("unitPrice") double unitPrice,
                             HttpSession session,
                             RedirectAttributes redirectAttributes) {
        try {
            String accessToken = (String) session.getAttribute("accessToken");
            String realmId = (String) session.getAttribute("realmId");

            if (accessToken == null || realmId == null) {
                redirectAttributes.addFlashAttribute("error", "Please connect to QuickBooks first.");
                return "redirect:/";
            }

            Map<String, Object> created = apiService.createItem(accessToken, realmId, name, unitPrice);

            // Refresh items in session
            Map<String, Object> itemsResult = apiService.getItems(accessToken, realmId);
            session.setAttribute("items", itemsResult.get("items"));
            session.setAttribute("itemNames", itemsResult.get("itemNames"));
            session.setAttribute("itemMap", itemsResult.get("itemMap"));

            redirectAttributes.addFlashAttribute("success", "Item created: " + created.get("name") + " (ID: " + created.get("id") + ")");
            return "redirect:/";
        } catch (RuntimeException e) {
            String realm = (String) session.getAttribute("realmId");
            String env = config.getEnvironment();
            redirectAttributes.addFlashAttribute("error", e.getMessage() + " [env=" + env + ", realmId=" + (realm != null ? realm : "(none)") + "]");
            return "redirect:/";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to create item. Please try again.");
            return "redirect:/";
        }
    }
    
    /**
     * Test endpoint to verify environment configuration and URLs
     * Use this to confirm environment switching is working
     */
    @GetMapping("/test-environment")
    @ResponseBody
    public Map<String, String> testEnvironment(HttpSession session) {
        Map<String, String> result = new java.util.HashMap<>();
        result.put("environment", config.getEnvironment());
        result.put("baseUrl", config.getBaseUrl());
        result.put("graphqlUrl", config.getGraphqlUrl());
        result.put("sampleDeepLink", config.getInvoiceDeepLink("123", "456"));
        result.put("clientId", config.getClientId());
        result.put("redirectUri", config.getRedirectUri());
        Object realmId = session != null ? session.getAttribute("realmId") : null;
        Object authenticated = session != null ? session.getAttribute("authenticated") : null;
        result.put("realmId", realmId != null ? realmId.toString() : "(none)");
        Object grantedScope = session != null ? session.getAttribute("grantedScope") : null;
        java.util.List<String> requested = config.getScopes();
        result.put("scope", grantedScope != null ? grantedScope.toString() : "(unknown)\n(If empty, re-consent to ensure accounting scope)");
        if (requested != null && !requested.isEmpty()) {
            result.put("requestedScopes", String.join(" ", requested));
        }
        result.put("authenticated", authenticated != null ? authenticated.toString() : "false");
        return result;
    }
    
    /**
     * Nuclear option: Force clear ALL session data and show current state
     * Use this when normal logout doesn't work
     */
    @GetMapping("/force-clear-session")
    @ResponseBody
    public Map<String, Object> forceClearSession(HttpSession session) {
        Map<String, Object> result = new java.util.HashMap<>();
        
        // Show what's currently in session BEFORE clearing
        result.put("beforeClear_sessionId", session.getId());
        result.put("beforeClear_realmId", session.getAttribute("realmId"));
        result.put("beforeClear_accessToken", session.getAttribute("accessToken") != null ? "present" : "null");
        
        // Nuclear clear - remove everything
        session.removeAttribute("accessToken");
        session.removeAttribute("refreshToken");
        session.removeAttribute("realmId");
        session.removeAttribute("authenticated");
        session.removeAttribute("authCompleted");
        session.removeAttribute("authTimestamp");
        session.removeAttribute("lastProcessedAuthCode");
        session.removeAttribute("customer_map");
        session.removeAttribute("items");
        session.removeAttribute("itemNames");
        session.removeAttribute("itemMap");
        session.removeAttribute("project");
        session.removeAttribute("invoiceId");
        session.removeAttribute("invoiceDeepLink");
        session.removeAttribute("invoiceProjectId");
        session.removeAttribute("invoiceAmount");
        session.removeAttribute("invoiceNumber");
        
        // Invalidate session completely
        session.invalidate();
        
        result.put("status", "Session completely cleared and invalidated");
        result.put("action", "Please restart browser and try OAuth again");
        
        return result;
    }

    /**
     * Refresh access token using stored refresh token.
     */
    @PostMapping("/refresh-token")
    public String refreshToken(HttpSession session, RedirectAttributes redirectAttributes) {
        try {
            String refreshToken = (String) session.getAttribute("refreshToken");
            if (refreshToken == null || refreshToken.trim().isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "No refresh token available. Please re-authenticate.");
                return "redirect:/";
            }
            Map<String, Object> refreshed = oauthService.refreshToken(refreshToken);
            String accessToken = (String) refreshed.get("access_token");
            if (accessToken != null && !accessToken.isEmpty()) {
                session.setAttribute("accessToken", "Bearer " + accessToken);
            }
            if (refreshed.get("refresh_token") != null) {
                session.setAttribute("refreshToken", refreshed.get("refresh_token"));
            }
            // Show a short, masked preview so users see an update without exposing secrets fully
            String preview = accessToken != null && accessToken.length() > 10
                ? accessToken.substring(0, 6) + "…" + accessToken.substring(accessToken.length() - 4)
                : "(updated)";
            redirectAttributes.addFlashAttribute("success", "Access token refreshed: " + preview);
            redirectAttributes.addFlashAttribute("successType", "refresh");
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to refresh token.");
        }
        return "redirect:/";
    }
}