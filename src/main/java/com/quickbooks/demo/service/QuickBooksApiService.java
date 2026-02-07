package com.quickbooks.demo.service;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intuit.ipp.data.Account;
import com.intuit.ipp.data.Customer;
import com.intuit.ipp.data.Invoice;
import com.intuit.ipp.data.Item;
import com.intuit.ipp.data.ItemTypeEnum;
import com.intuit.ipp.data.Line;
import com.intuit.ipp.data.LineDetailTypeEnum;
import com.intuit.ipp.data.ReferenceType;
import com.intuit.ipp.data.SalesItemLineDetail;
import com.intuit.ipp.data.Vendor;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.services.DataService;
import com.intuit.ipp.services.QueryResult;
import com.quickbooks.demo.config.QuickBooksConfig;
import com.quickbooks.demo.model.QuickBooksContext;
import com.quickbooks.demo.service.client.QuickBooksRestClient;
import com.quickbooks.demo.service.client.QuickBooksSdkClient;


@Service
public class QuickBooksApiService {
    
    @Autowired
    private QuickBooksConfig config;
    
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RestTemplate restTemplate; // For GraphQL calls
    
    @Autowired
    private QuickBooksRestClient restClient;
    
    @Autowired
    private QuickBooksSdkClient sdkClient;
    
    private String ensureNoTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    
    private ResponseEntity<String> exchangeWithRetry(String url, HttpMethod method, HttpEntity<?> entity) {
        RetryTemplate retry = RetryTemplate.builder()
            .maxAttempts(3)
            .fixedBackoff(500)
            .retryOn(ResourceAccessException.class)
            .retryOn(HttpClientErrorException.TooManyRequests.class)
            .retryOn(RuntimeException.class)
            .build();
        return retry.execute(ctx -> {
            ResponseEntity<String> response = restTemplate.exchange(url, method, entity, String.class);
            HttpStatusCode status = response.getStatusCode();
            if (status.is2xxSuccessful()) {
                return response;
            }
            if (status.value() == 429 || status.is5xxServerError()) {
                throw new RuntimeException("Transient response: " + status.value());
            }
            return response;
        });
    }

    private QuickBooksContext ctx(String accessToken, String realmId) {
        return QuickBooksContext.of(accessToken, realmId);
    }
    
    /**
     * Get customers from QuickBooks
     * 
     */
    public Map<String, Object> getCustomers(String accessToken, String realmId) {
        // Validate required parameters
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }
        
        try {
            // Query via REST with a minimal, parser-safe SELECT (mirror working project)
            // Avoid MAXRESULTS clause which some QBO tenants reject without STARTPOSITION
            String query = "Select * from Customer where Job = false";
            String body = restClient.query(ctx(accessToken, realmId), query);
            JsonNode responseData = objectMapper.readTree(body);
            JsonNode queryResponse = responseData.get("QueryResponse");

            List<String> customerNames = new ArrayList<>();
            Map<String, String> customerMap = new HashMap<>();
            List<Map<String, Object>> customers = new ArrayList<>();

            if (queryResponse != null && queryResponse.has("Customer")) {
                JsonNode customersNode = queryResponse.get("Customer");
                for (JsonNode customerNode : customersNode) {
                    String id = customerNode.path("Id").asText();
                    String name = customerNode.has("DisplayName")
                        ? customerNode.get("DisplayName").asText()
                        : customerNode.path("FullyQualifiedName").asText("");
                    customerNames.add(name);
                    customerMap.put(id, name);
                    Map<String, Object> customer = new HashMap<>();
                    customer.put("id", id);
                    customer.put("name", name);
                    customers.add(customer);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("customers", customers);
            result.put("customerNames", customerNames);
            result.put("customerMap", customerMap);
            return result;

        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("Failed to get customers: " + e.getMessage(), e);
        }
    }

    /**
     * Get all accounts (sample fields) via Accounting REST API query endpoint.
     */
    public Map<String, Object> getAccounts(String accessToken, String realmId) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }

        try {
            String query = "Select Id, Name, AccountType, AccountSubType, CurrentBalance, FullyQualifiedName from Account where Active = true";
            String body = restClient.query(ctx(accessToken, realmId), query);
            JsonNode root = objectMapper.readTree(body);
            JsonNode qr = root.path("QueryResponse");
            List<Map<String, Object>> accounts = new ArrayList<>();
            if (qr.has("Account") && qr.get("Account").isArray()) {
                for (JsonNode node : qr.get("Account")) {
                    Map<String, Object> acct = new HashMap<>();
                    acct.put("id", node.path("Id").asText());
                    acct.put("name", node.path("Name").asText());
                    acct.put("type", node.path("AccountType").asText());
                    acct.put("subType", node.path("AccountSubType").asText());
                    acct.put("fullyQualifiedName", node.path("FullyQualifiedName").asText());
                    acct.put("currentBalance", node.path("CurrentBalance").asDouble(0));
                    accounts.add(acct);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("accounts", accounts);
            result.put("count", accounts.size());
            return result;
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Failed to fetch accounts: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse accounts response: " + e.getMessage(), e);
        }
    }
    

    public Map<String, Object> getItems(String accessToken, String realmId) {
        // Validate required parameters
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }
        
        try {
            // Only return items usable on sales transactions (exclude Category)
            String query = "Select Id, Name, Type from Item where Active = true and Type in ('Service','NonInventory','Inventory') MAXRESULTS 25";
            String body = restClient.query(ctx(accessToken, realmId), query);
            JsonNode responseData = objectMapper.readTree(body);
            JsonNode queryResponse = responseData.get("QueryResponse");
            
            if (queryResponse != null && queryResponse.has("Item")) {
                JsonNode itemsNode = queryResponse.get("Item");
                
                List<String> itemNames = new ArrayList<>();
                Map<String, String> itemMap = new HashMap<>();
                List<Map<String, Object>> items = new ArrayList<>();
                
                for (JsonNode itemNode : itemsNode) {
                    String type = itemNode.has("Type") ? itemNode.get("Type").asText() : null;
                    if (type != null && "Category".equalsIgnoreCase(type)) {
                        continue; // skip categories
                    }
                    String name = itemNode.get("Name").asText();
                    String id = itemNode.get("Id").asText();
                    
                    itemNames.add(name);
                    itemMap.put(id, name);
                    
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", id);
                    item.put("name", name);
                    item.put("type", type);
                    items.add(item);
                }
                
                Map<String, Object> result = new HashMap<>();
                result.put("items", items);
                result.put("itemNames", itemNames);
                result.put("itemMap", itemMap);
                
                return result;
            } else {
                // No items found - return empty result
                Map<String, Object> result = new HashMap<>();
                result.put("items", new ArrayList<>());
                result.put("itemNames", new ArrayList<>());
                result.put("itemMap", new HashMap<>());
                return result;
            }
            
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("Failed to get items: " + e.getMessage(), e);
        }
    }
    
    /**
     * Create invoice using QuickBooks Java SDK
     * This is the proper way to create invoices using the official SDK
     */
    public Map<String, Object> createInvoice(String accessToken, String realmId, 
                                           String customerId, String itemId, String itemName, 
                                           String projectId, int quantity, double unitPrice, String description) {
        
        // Validate required parameters
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }
        if (customerId == null || customerId.trim().isEmpty()) {
            throw new RuntimeException("Customer ID is required");
        }
        if (itemId == null || itemId.trim().isEmpty()) {
            throw new RuntimeException("Item ID is required");
        }
        if (projectId == null || projectId.trim().isEmpty()) {
            throw new RuntimeException("Project ID is required");
        }
        
        try {
            DataService dataService = sdkClient.dataService(ctx(accessToken, realmId));
            
            // Resolve Accounting ProjectRef (Customer with IsProject=true).
            // The UI supplies GraphQL ProjectManagement id. We must map it to the accounting
            // Customer (project) id used by ProjectRef.
            String projectRefId = projectId;
            try {
                // 1) Quick probe: maybe the provided id is already an accounting Customer id
                QueryResult probe = dataService.executeQuery(
                    "select Id from Customer where IsProject = true and Id = '" + projectId + "'"
                );
                boolean alreadyCustomer = (probe != null && probe.getEntities() != null && !probe.getEntities().isEmpty());

                if (!alreadyCustomer) {
                    // 2) Fetch GraphQL project to get its canonical name and parent customer id
                    Map<String, Object> gqlProject = getProjectById(accessToken, realmId, projectId);
                    String projectName = (String) gqlProject.get("name");
                    String parentCustomerId = null;
                    Object cust = gqlProject.get("customer");
                    if (cust instanceof java.util.Map<?, ?> m) {
                        Object cid = m.get("id");
                        parentCustomerId = cid != null ? cid.toString() : null;
                    }

                    if (projectName != null && !projectName.trim().isEmpty()) {
                        // Escape single quotes in name for query safety
                        String safeName = projectName.replace("'", "''");
                        StringBuilder q = new StringBuilder("select Id, DisplayName, ParentRef from Customer where IsProject = true and Active = true and DisplayName = '")
                                .append(safeName).append("'");
                        if (parentCustomerId != null && !parentCustomerId.trim().isEmpty()) {
                            q.append(" and ParentRef = '").append(parentCustomerId).append("'");
                        }
                        QueryResult qr = dataService.executeQuery(q.toString());
                        if (qr != null && qr.getEntities() != null && !qr.getEntities().isEmpty()) {
                            Object first = qr.getEntities().get(0);
                            if (first instanceof Customer accProj) {
                                projectRefId = accProj.getId();
                            }
                        } else {
                            // As a fallback, attempt by FullyQualifiedName if parent name is involved
                            // No-op if not found; SDK will throw a precise error later
                        }
                    }
                }
            } catch (FMSException | RuntimeException ignore) {
                // Proceed; SDK will report invalid ProjectRef if resolution fails
            }
            // GraphQL lookup may fail transiently; proceed with provided id

            // Create the Invoice object using SDK classes
            Invoice invoice = new Invoice();
            
            // Set customer reference
            ReferenceType customerRef = new ReferenceType();
            customerRef.setValue(customerId);
            invoice.setCustomerRef(customerRef);
            
            // Set ProjectRef so the invoice deep-links to the Project in supported environments
            ReferenceType projectRef = new ReferenceType();
            projectRef.setValue(projectRefId);
            invoice.setProjectRef(projectRef);
            
            // Create line item using SDK classes
            Line line = new Line();
            line.setDetailType(LineDetailTypeEnum.SALES_ITEM_LINE_DETAIL);
            
            // Calculate total amount
            BigDecimal totalAmount = BigDecimal.valueOf(quantity).multiply(BigDecimal.valueOf(unitPrice));
            line.setAmount(totalAmount);
            
            if (description != null && !description.trim().isEmpty()) {
                line.setDescription(description);
            }
            
            // Create SalesItemLineDetail
            SalesItemLineDetail salesItemLineDetail = new SalesItemLineDetail();
            
            // Set item reference
            ReferenceType itemRef = new ReferenceType();
            itemRef.setValue(itemId);
            itemRef.setName(itemName);
            salesItemLineDetail.setItemRef(itemRef);
            
            // Set quantity
            salesItemLineDetail.setQty(BigDecimal.valueOf(quantity));
            
            line.setSalesItemLineDetail(salesItemLineDetail);
            
            // Add line to invoice
            List<Line> lines = new ArrayList<>();
            lines.add(line);
            invoice.setLine(lines);
            
            // Create the invoice using the DataService
            Invoice createdInvoice = dataService.add(invoice);
            
            // Extract results
            String invoiceId = createdInvoice.getId();
            String deepLink = generateInvoiceDeepLink(invoiceId, realmId);
            
            // Return result map
            Map<String, Object> result = new HashMap<>();
            result.put("invoiceId", invoiceId);
            result.put("deepLink", deepLink);
            result.put("projectId", projectId);
            result.put("customerId", customerId);
            result.put("amount", totalAmount.doubleValue());
            result.put("docNumber", createdInvoice.getDocNumber()); // Invoice number
            result.put("totalAmt", createdInvoice.getTotalAmt()); // Total from QuickBooks
            
            return result;
            
        } catch (FMSException e) {
            // Include environment, realm, and base URL to aid debugging
            String errorMessage = "QuickBooks API Error (create invoice): " + e.getMessage()
                + " [env=" + config.getEnvironment()
                + ", realmId=" + realmId
                + ", baseUrl=" + ensureNoTrailingSlash(config.getBaseUrl()) + "]";
            throw new RuntimeException(errorMessage, e);
        } catch (Exception e) {
            String errorMessage = "Failed to create invoice using SDK: " + e.getMessage();
            throw new RuntimeException(errorMessage, e);
        }
    }

    /**
     * Generate deep link to view invoice in QuickBooks UI
     * Uses config method for consistency
     */
    public String generateInvoiceDeepLink(String invoiceId, String realmId) {
        if (invoiceId == null || invoiceId.trim().isEmpty()) {
            throw new RuntimeException("Invoice ID is required for deep link generation");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required for deep link generation");
        }
        
        return config.getInvoiceDeepLink(invoiceId, realmId);
    }
    
    /**
     * Resolve the Accounting Project (Customer with IsProject=true) Id for a given GraphQL project
     * name and optional parent customer id. Returns null if not found.
     */
    public String resolveAccountingProjectId(String accessToken, String realmId, String projectName, String parentCustomerId) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }
        if (projectName == null || projectName.trim().isEmpty()) {
            return null;
        }
        try {
            DataService dataService = sdkClient.dataService(ctx(accessToken, realmId));

            String safeName = projectName.replace("'", "''");
            StringBuilder q = new StringBuilder("select Id, DisplayName, ParentRef from Customer where IsProject = true and Active = true and DisplayName = '")
                .append(safeName).append("'");
            if (parentCustomerId != null && !parentCustomerId.trim().isEmpty()) {
                q.append(" and ParentRef = '").append(parentCustomerId).append("'");
            }
            QueryResult qr = dataService.executeQuery(q.toString());
            if (qr != null && qr.getEntities() != null && !qr.getEntities().isEmpty()) {
                Object first = qr.getEntities().get(0);
                if (first instanceof Customer c) {
                    return c.getId();
                }
            }
            return null;
        } catch (FMSException e) {
            return null;
        }
    }

    /**
     * Create a Customer using the QuickBooks Java SDK.
     */
    public Map<String, Object> createCustomer(String accessToken, String realmId, String displayName, String email, String phone) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new RuntimeException("Customer display name is required");
        }

        try {
            DataService dataService = sdkClient.dataService(ctx(accessToken, realmId));

            Customer customer = new Customer();
            customer.setDisplayName(displayName);
            if (email != null && !email.trim().isEmpty()) {
                com.intuit.ipp.data.EmailAddress addr = new com.intuit.ipp.data.EmailAddress();
                addr.setAddress(email);
                customer.setPrimaryEmailAddr(addr);
            }
            if (phone != null && !phone.trim().isEmpty()) {
                com.intuit.ipp.data.TelephoneNumber tel = new com.intuit.ipp.data.TelephoneNumber();
                tel.setFreeFormNumber(phone);
                customer.setPrimaryPhone(tel);
            }

            Customer created = dataService.add(customer);

            Map<String, Object> result = new HashMap<>();
            result.put("id", created.getId());
            result.put("name", created.getDisplayName());
            return result;

        } catch (FMSException e) {
            String errorMessage = "QuickBooks API Error (create customer): " + e.getMessage()
                + " [env=" + config.getEnvironment()
                + ", realmId=" + realmId
                + ", baseUrl=" + ensureNoTrailingSlash(config.getBaseUrl()) + "]";
            throw new RuntimeException(errorMessage, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create customer: " + e.getMessage(), e);
        }
    }

    /**
     * Create an Estimate via Accounting REST API and link to a Project using ProjectRef.
     */
    public Map<String, Object> createEstimate(String accessToken,
                                              String realmId,
                                              String customerId,
                                              String itemId,
                                              String projectId,
                                              int quantity,
                                              double unitPrice,
                                              String description) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }
        if (customerId == null || customerId.trim().isEmpty()) {
            throw new RuntimeException("Customer ID is required");
        }
        if (itemId == null || itemId.trim().isEmpty()) {
            throw new RuntimeException("Item ID is required");
        }
        if (projectId == null || projectId.trim().isEmpty()) {
            throw new RuntimeException("Project ID is required");
        }

        try {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("TxnDate", java.time.LocalDate.now().toString());
            java.util.Map<String, Object> currency = new java.util.HashMap<>();
            currency.put("value", "USD");
            currency.put("name", "United States Dollar");
            payload.put("CurrencyRef", currency);

            // Project and customer linkage
            java.util.Map<String, Object> projectRef = new java.util.HashMap<>();
            projectRef.put("value", projectId);
            payload.put("ProjectRef", projectRef);

            java.util.Map<String, Object> customerRef = new java.util.HashMap<>();
            customerRef.put("value", customerId);
            payload.put("CustomerRef", customerRef);

            java.math.BigDecimal total = java.math.BigDecimal.valueOf(quantity).multiply(java.math.BigDecimal.valueOf(unitPrice));

            // Line
            java.util.Map<String, Object> salesDetail = new java.util.HashMap<>();
            java.util.Map<String, Object> itemRef = new java.util.HashMap<>();
            itemRef.put("value", itemId);
            salesDetail.put("ItemRef", itemRef);
            salesDetail.put("UnitPrice", unitPrice);
            salesDetail.put("Qty", quantity);
            java.util.Map<String, Object> taxCode = new java.util.HashMap<>();
            taxCode.put("value", "NON");
            salesDetail.put("TaxCodeRef", taxCode);

            java.util.Map<String, Object> line1 = new java.util.HashMap<>();
            line1.put("Id", "1");
            line1.put("LineNum", 1);
            if (description != null && !description.trim().isEmpty()) {
                line1.put("Description", description);
            }
            line1.put("Amount", total);
            line1.put("DetailType", "SalesItemLineDetail");
            line1.put("SalesItemLineDetail", salesDetail);

            java.util.Map<String, Object> subTotal = new java.util.HashMap<>();
            subTotal.put("Amount", total);
            subTotal.put("DetailType", "SubTotalLineDetail");
            subTotal.put("SubTotalLineDetail", new java.util.HashMap<>());

            java.util.List<java.util.Map<String, Object>> lines = new java.util.ArrayList<>();
            lines.add(line1);
            lines.add(subTotal);
            payload.put("Line", lines);

            String body = restClient.postJson(ctx(accessToken, realmId), "/estimate", payload);
            JsonNode root = objectMapper.readTree(body);
            JsonNode est = root.path("Estimate");
            if (est.isMissingNode()) {
                // Some responses nest under top-level; attempt alt path
                est = root.path("Estimate");
            }
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("estimateId", est.path("Id").asText(null));
            result.put("totalAmt", est.path("TotalAmt").asDouble(0));
            result.put("projectId", projectId);
            result.put("customerId", customerId);
            return result;
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Failed to create estimate: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse estimate response: " + e.getMessage(), e);
        }
    }

    /**
     * Create a Sales Receipt via Accounting REST API with ProjectRef linkage.
     */
    public Map<String, Object> createSalesReceipt(String accessToken,
                                                  String realmId,
                                                  String customerId,
                                                  String itemId,
                                                  String projectId,
                                                  int quantity,
                                                  double unitPrice,
                                                  String description) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }
        if (customerId == null || customerId.trim().isEmpty()) {
            throw new RuntimeException("Customer ID is required");
        }
        if (itemId == null || itemId.trim().isEmpty()) {
            throw new RuntimeException("Item ID is required");
        }
        if (projectId == null || projectId.trim().isEmpty()) {
            throw new RuntimeException("Project ID is required");
        }
        if (quantity <= 0 || unitPrice < 0) {
            throw new RuntimeException("Quantity must be > 0 and UnitPrice >= 0");
        }

        try {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("TxnDate", java.time.LocalDate.now().toString());
            java.util.Map<String, Object> currency = new java.util.HashMap<>();
            currency.put("value", "USD");
            currency.put("name", "United States Dollar");
            payload.put("CurrencyRef", currency);

            // CustomerRef
            java.util.Map<String, Object> customerRef = new java.util.HashMap<>();
            customerRef.put("value", customerId);
            payload.put("CustomerRef", customerRef);

            // Lines
            java.math.BigDecimal total = java.math.BigDecimal.valueOf(quantity).multiply(java.math.BigDecimal.valueOf(unitPrice));
            java.util.Map<String, Object> line1 = new java.util.HashMap<>();
            line1.put("Id", "1");
            line1.put("LineNum", 1);
            if (description != null && !description.trim().isEmpty()) {
                line1.put("Description", description);
            }
            line1.put("Amount", total);
            line1.put("DetailType", "SalesItemLineDetail");
            // ProjectRef on the line
            java.util.Map<String, Object> projRef = new java.util.HashMap<>();
            projRef.put("value", projectId);
            line1.put("ProjectRef", projRef);
            // SalesItemLineDetail
            java.util.Map<String, Object> salesDetail = new java.util.HashMap<>();
            java.util.Map<String, Object> itemRef = new java.util.HashMap<>();
            itemRef.put("value", itemId);
            salesDetail.put("ItemRef", itemRef);
            salesDetail.put("UnitPrice", unitPrice);
            salesDetail.put("Qty", quantity);
            java.util.Map<String, Object> taxCode = new java.util.HashMap<>();
            taxCode.put("value", "NON");
            salesDetail.put("TaxCodeRef", taxCode);
            line1.put("SalesItemLineDetail", salesDetail);

            java.util.Map<String, Object> subTotal = new java.util.HashMap<>();
            subTotal.put("Amount", total);
            subTotal.put("DetailType", "SubTotalLineDetail");
            subTotal.put("SubTotalLineDetail", new java.util.HashMap<>());

            java.util.List<java.util.Map<String, Object>> lines = new java.util.ArrayList<>();
            lines.add(line1);
            lines.add(subTotal);
            payload.put("Line", lines);

            String body = restClient.postJson(ctx(accessToken, realmId), "/salesreceipt", payload);
            JsonNode root = objectMapper.readTree(body);
            JsonNode sr = root.path("SalesReceipt");
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("salesReceiptId", sr.path("Id").asText(null));
            result.put("totalAmt", sr.path("TotalAmt").asDouble(0));
            result.put("projectId", projectId);
            // Optional deep link for SR
            result.put("deepLink", "https://app.qbo.intuit.com/app/salesreceipt?txnId=" + sr.path("Id").asText(""));
            return result;
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Failed to create sales receipt: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse sales receipt response: " + e.getMessage(), e);
        }
    }

    /**
     * Create a Bill via Accounting REST API and link to a Project using ProjectRef.
     */
    public Map<String, Object> createBill(String accessToken,
                                          String realmId,
                                          String vendorId,
                                          String expenseAccountId,
                                          String projectId,
                                          double amount,
                                          String description) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }
        if (vendorId == null || vendorId.trim().isEmpty()) {
            throw new RuntimeException("Vendor ID is required");
        }
        if (expenseAccountId == null || expenseAccountId.trim().isEmpty()) {
            throw new RuntimeException("Expense Account ID is required");
        }
        if (projectId == null || projectId.trim().isEmpty()) {
            throw new RuntimeException("Project ID is required");
        }
        if (amount <= 0) {
            throw new RuntimeException("Amount must be > 0");
        }

        try {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("TxnDate", java.time.LocalDate.now().toString());

            // Line with AccountBasedExpenseLineDetail and ProjectRef
            java.util.Map<String, Object> line = new java.util.HashMap<>();
            line.put("DetailType", "AccountBasedExpenseLineDetail");
            line.put("Amount", amount);
            line.put("Id", "1");
            java.util.Map<String, Object> projRef = new java.util.HashMap<>();
            projRef.put("value", projectId);
            line.put("ProjectRef", projRef);
            if (description != null && !description.trim().isEmpty()) {
                line.put("Description", description);
            }

            java.util.Map<String, Object> abd = new java.util.HashMap<>();
            java.util.Map<String, Object> acctRef = new java.util.HashMap<>();
            acctRef.put("value", expenseAccountId);
            abd.put("AccountRef", acctRef);
            line.put("AccountBasedExpenseLineDetail", abd);

            java.util.List<java.util.Map<String, Object>> lines = new java.util.ArrayList<>();
            lines.add(line);
            payload.put("Line", lines);

            // VendorRef
            java.util.Map<String, Object> vendorRef = new java.util.HashMap<>();
            vendorRef.put("value", vendorId);
            payload.put("VendorRef", vendorRef);

            String body = restClient.postJson(ctx(accessToken, realmId), "/bill", payload);
            JsonNode root = objectMapper.readTree(body);
            JsonNode bill = root.path("Bill");
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("billId", bill.path("Id").asText(null));
            result.put("totalAmt", bill.path("TotalAmt").asDouble(0));
            result.put("projectId", projectId);
            result.put("vendorId", vendorId);
            // convenience deep link for UI
            result.put("deepLink", "https://app.qbo.intuit.com/app/bill?txnId=" + bill.path("Id").asText(""));
            return result;
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Failed to create bill: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse bill response: " + e.getMessage(), e);
        }
    }
    /**
     * Create an Item (Service) using the QuickBooks Java SDK.
     * Automatically locates an Income account if none is provided.
     */
    public Map<String, Object> createItem(String accessToken, String realmId, String name, double unitPrice) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new RuntimeException("Item name is required");
        }
        if (unitPrice < 0) {
            throw new RuntimeException("Unit price must be >= 0");
        }

        try {
            DataService dataService = sdkClient.dataService(ctx(accessToken, realmId));

            String incomeAccountId = findIncomeAccountId(dataService);
            if (incomeAccountId == null) {
                throw new RuntimeException("Could not find an Income account to assign to the item");
            }

            Item item = new Item();
            item.setName(name);
            item.setType(ItemTypeEnum.SERVICE);
            item.setUnitPrice(BigDecimal.valueOf(unitPrice));
            ReferenceType incomeRef = new ReferenceType();
            incomeRef.setValue(incomeAccountId);
            item.setIncomeAccountRef(incomeRef);

            Item created = dataService.add(item);

            Map<String, Object> result = new HashMap<>();
            result.put("id", created.getId());
            result.put("name", created.getName());
            result.put("unitPrice", created.getUnitPrice());
            return result;

        } catch (FMSException e) {
            String errorMessage = "QuickBooks API Error (create item): " + e.getMessage()
                + " [env=" + config.getEnvironment()
                + ", realmId=" + realmId
                + ", baseUrl=" + ensureNoTrailingSlash(config.getBaseUrl()) + "]";
            throw new RuntimeException(errorMessage, e);
        } catch (RuntimeException e) {
            throw new RuntimeException("Failed to create item: " + e.getMessage(), e);
        }
    }

    /**
     * Attempts to find a valid Income account id for use when creating items.
     */
    private String findIncomeAccountId(DataService dataService) throws FMSException {
        // Prefer a generic income account if available
        QueryResult qr = dataService.executeQuery("select * from Account where AccountType = 'Income' and Active = true");
        if (qr != null && qr.getEntities() != null && !qr.getEntities().isEmpty()) {
            Object first = qr.getEntities().get(0);
            if (first instanceof Account acc) {
                return acc.getId();
            }
        }
        return null;
    }


    /**
     * Fetch Vendors using the QuickBooks SDK (for Step 8 dropdown).
     */
    public Map<String, Object> getVendors(String accessToken, String realmId) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }
        try {
            DataService dataService = sdkClient.dataService(ctx(accessToken, realmId));

            QueryResult qr = dataService.executeQuery("select Id, DisplayName from Vendor where Active = true");
            java.util.List<java.util.Map<String, Object>> vendors = new java.util.ArrayList<>();
            if (qr != null && qr.getEntities() != null) {
                for (Object entity : qr.getEntities()) {
                    if (entity instanceof Vendor v) {
                        java.util.Map<String, Object> item = new java.util.HashMap<>();
                        item.put("id", v.getId());
                        item.put("name", v.getDisplayName());
                        vendors.add(item);
                    }
                }
            }
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("vendors", vendors);
            return result;
        } catch (FMSException e) {
            String errorMessage = "QuickBooks API Error (get vendors): " + e.getMessage()
                + " [env=" + config.getEnvironment()
                + ", realmId=" + realmId
                + ", baseUrl=" + ensureNoTrailingSlash(config.getBaseUrl()) + "]";
            throw new RuntimeException(errorMessage, e);
        }
    }

    /**
     * Fetch Expense accounts (and COGS) for use in Bill lines.
     */
    public Map<String, Object> getExpenseAccounts(String accessToken, String realmId) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }
        try {
            DataService dataService = sdkClient.dataService(ctx(accessToken, realmId));

            QueryResult qr = dataService.executeQuery("select Id, Name, AccountType from Account where Active = true and AccountType in ('Expense','Cost of Goods Sold')");
            java.util.List<java.util.Map<String, Object>> accounts = new java.util.ArrayList<>();
            if (qr != null && qr.getEntities() != null) {
                for (Object entity : qr.getEntities()) {
                    if (entity instanceof Account a) {
                        java.util.Map<String, Object> item = new java.util.HashMap<>();
                        item.put("id", a.getId());
                        item.put("name", a.getName());
                        item.put("type", a.getAccountType() != null ? a.getAccountType().name() : "");
                        accounts.add(item);
                    }
                }
            }
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("accounts", accounts);
            return result;
        } catch (FMSException e) {
            String errorMessage = "QuickBooks API Error (get expense accounts): " + e.getMessage()
                + " [env=" + config.getEnvironment()
                + ", realmId=" + realmId
                + ", baseUrl=" + ensureNoTrailingSlash(config.getBaseUrl()) + "]";
            throw new RuntimeException(errorMessage, e);
        }
    }

    public Map<String, Object> createProject(String accessToken, String customerName, String customerId, String projectName) {
        // Validate required parameters
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (customerName == null || customerName.trim().isEmpty()) {
            throw new RuntimeException("customerName is required");
        }
        if (customerId == null || customerId.trim().isEmpty()) {
            throw new RuntimeException("Customer ID is required");
        }
        
        try {
            // Read GraphQL mutation from file 
            String mutation = getProjectMutation();
            
            // Prepare variables
            Map<String, Object> variables = prepareProjectVariables(customerName, customerId, projectName);
            
            // Prepare GraphQL request
            Map<String, Object> graphqlRequest = new HashMap<>();
            graphqlRequest.put("query", mutation);
            graphqlRequest.put("variables", variables);
            
            // Create HTTP headers for GraphQL request
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(graphqlRequest, headers);
            
            // Make GraphQL request using RestTemplate
            ResponseEntity<String> response = exchangeWithRetry(config.getGraphqlUrl(), HttpMethod.POST, request);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode responseData = objectMapper.readTree(response.getBody());
                
                // Handle GraphQL errors
                if (responseData.has("errors")) {
                    JsonNode errors = responseData.get("errors");
                    String errorMessage = errors.isArray() && errors.size() > 0 ? 
                        errors.get(0).get("message").asText() : "GraphQL validation failed";
                    
                    // Check if this is a QuickBooks backend infrastructure issue
                    if (errorMessage.contains("Could not open JPA EntityManager") || 
                        errorMessage.contains("Unable to acquire JDBC Connection")) {
                        throw new RuntimeException("QuickBooks backend service is experiencing database connectivity issues. Please try again later or contact QuickBooks Developer Support if the issue persists.");
                    }
                    
                    throw new RuntimeException("GraphQL error: " + errorMessage);
                }
                
                JsonNode projectData = responseData.get("data").get("projectManagementCreateProject");
                
                Map<String, Object> result = new HashMap<>();
                result.put("id", projectData.get("id").asText());
                result.put("name", projectData.get("name").asText());
                result.put("description", projectData.get("description").asText());
                result.put("status", projectData.get("status").asText());
                result.put("startDate", projectData.get("startDate").asText());
                result.put("dueDate", projectData.get("dueDate").asText());
                
                return result;
            }
            
            throw new RuntimeException("Failed to create project: " + response.getBody());
            
        } catch (HttpClientErrorException e) {
            // Map common authorization failures to clearer messages
            int status = e.getStatusCode().value();
            String body = e.getResponseBodyAsString();
            
            if (status == 401) {
                throw new RuntimeException("Unauthorized (401): Access token invalid or expired. Please reconnect to QuickBooks.");
            } else if (status == 403) {
                String lower = body != null ? body.toLowerCase() : "";
                if (lower.contains("insufficient_scope") || lower.contains("insufficient scope") || lower.contains("permission")) {
                    throw new RuntimeException("Insufficient scope: missing 'project-management.project'. Re-add this scope and re-authenticate.");
                }
                throw new RuntimeException("Forbidden (403): Your app/user/company lacks permission to create projects. Ensure Projects feature is enabled and required scope is granted.");
            }
            throw new RuntimeException("Failed to create project: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("Failed to create project: " + e.getMessage(), e);
        }
    }
    

    /**
     * Read GraphQL mutation from file 
     */
    private String getProjectMutation() {
        try {
            ClassPathResource resource = new ClassPathResource("graphql/project.graphql");
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read GraphQL file: " + e.getMessage(), e);
        }
    }
    
    private String getProjectsListQuery() {
        try {
            ClassPathResource resource = new ClassPathResource("graphql/projects_list.graphql");
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read GraphQL list query: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> prepareProjectsListVariables() {
        try {
            ClassPathResource resource = new ClassPathResource("graphql/projects_list_variables.json");
            String json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(json);
            Map<String, Object> vars = new HashMap<>();
            // Default filter: no-op filter object; adjust fields as needed later
            Map<String, Object> filter = new HashMap<>();
            if (node.has("filter") && node.get("filter").isObject()) {
                vars.put("filter", objectMapper.convertValue(node.get("filter"), Map.class));
            } else {
                vars.put("filter", filter);
            }
            java.util.List<Object> orderBy = new java.util.ArrayList<>();
            if (node.has("orderBy") && node.get("orderBy").isArray()) {
                for (JsonNode ob : node.get("orderBy")) {
                    if (ob.isTextual()) {
                        orderBy.add(ob.asText());
                    } else if (ob.isObject()) {
                        orderBy.add(objectMapper.convertValue(ob, Map.class));
                    }
                }
            } else {
                // Fallback to a common enum-based default
                orderBy.add("DUE_DATE_DESC");
            }
            vars.put("orderBy", orderBy);
            return vars;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read projects list variables: " + e.getMessage(), e);
        }
    }

    private String readResource(String classpathLocation) {
        try {
            ClassPathResource resource = new ClassPathResource(classpathLocation);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read resource: " + classpathLocation + ": " + e.getMessage(), e);
        }
    }
    
    /**
     * Delete a project via GraphQL using id and version (soft-delete).
     */
    public Map<String, Object> deleteProject(String accessToken, String realmId, String id, Integer version) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (id == null || id.trim().isEmpty()) {
            throw new RuntimeException("Project ID is required");
        }
        try {
            String query = readResource("graphql/project_delete.graphql");
            Map<String, Object> variables = new HashMap<>();
            Map<String, Object> input = new HashMap<>();
            input.put("id", id);
            if (version != null) {
                input.put("version", version);
            }
            variables.put("input", input);

            Map<String, Object> graphqlRequest = new HashMap<>();
            graphqlRequest.put("query", query);
            graphqlRequest.put("variables", variables);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(graphqlRequest, headers);
            ResponseEntity<String> response = exchangeWithRetry(config.getGraphqlUrl(), HttpMethod.POST, request);
            if (!response.getStatusCode().is2xxSuccessful()) {
                int sc = response.getStatusCode().value();
                if (sc == 401) {
                    throw new RuntimeException("Unauthorized (401): Access token invalid or expired. Please reconnect to QuickBooks.");
                } else if (sc == 403) {
                    throw new RuntimeException("Forbidden (403): Missing scope 'project-management.project' or Projects not enabled.");
                }
                throw new RuntimeException("Failed to delete project: " + response.getStatusCode() + " - " + response.getBody());
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode data = root.path("data").path("projectManagementDeleteProject");
            if (data.isMissingNode() || data.isNull()) {
                if (root.has("errors")) {
                    JsonNode errors = root.get("errors");
                    String message = errors.isArray() && errors.size() > 0 ? errors.get(0).get("message").asText() : "GraphQL error";
                    throw new RuntimeException("GraphQL error: " + message);
                }
                throw new RuntimeException("No response for delete project");
            }

            Map<String, Object> result = new HashMap<>();
            if (data.has("id")) {
                result.put("id", data.path("id").asText());
                result.put("name", data.path("name").asText(null));
                result.put("version", data.path("version").asInt(0));
                result.put("deleted", data.path("deleted").asBoolean(false));
            } else if (data.has("message")) {
                throw new RuntimeException("Delete failed: " + data.path("message").asText());
            }
            return result;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            int status = e.getRawStatusCode();
            if (status == 401) {
                throw new RuntimeException("Unauthorized (401): Access token invalid or expired. Please reconnect to QuickBooks.", e);
            } else if (status == 403) {
                throw new RuntimeException("Forbidden (403): Missing scope 'project-management.project' or Projects not enabled.", e);
            }
            throw new RuntimeException("Failed to delete project: " + status + " - " + e.getResponseBodyAsString(), e);
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("Failed to delete project: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> prepareProjectVariables(String customerName, String customerId, String projectName) {
        try {
            
            ClassPathResource resource = new ClassPathResource("graphql/project_variables.json");
            String jsonContent = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode templateData = objectMapper.readTree(jsonContent);
            JsonNode template = templateData.get("template");
            
            String projectId = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime futureDate = now.plusYears(5);
            
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            
            Map<String, Object> variables = new HashMap<>();
            
            // Use template values with dynamic substitution
            String nameTemplate = template.get("name").asText();
            String descriptionTemplate = template.get("description").asText();
            
            String resolvedName = (projectName != null && !projectName.trim().isEmpty())
                ? projectName.trim()
                : nameTemplate.replace("{uuid}", projectId);
            variables.put("name", resolvedName);
            variables.put("description", descriptionTemplate.replace("{customerName}", customerName));
            variables.put("startDate", now.format(formatter));
            variables.put("dueDate", futureDate.format(formatter));
            variables.put("status", template.get("status").asText());
            variables.put("priority", template.get("priority").asInt());
            variables.put("pinned", template.get("pinned").asBoolean());
            
            Map<String, Object> customer = new HashMap<>();
            customer.put("id", Integer.valueOf(customerId));
            variables.put("customer", customer);
            
            return variables;
            
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("Failed to read project variables template: " + e.getMessage(), e);
        }
    }

    /**
     * List projects via GraphQL with pagination support.
     */
    public Map<String, Object> listProjects(String accessToken, String realmId, Integer first, String afterCursor,
                                            String startDateIso, String endDateIso) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }

        try {
            String query = getProjectsListQuery();

            Map<String, Object> variables = new HashMap<>();
            int pageSize = (first == null || first <= 0) ? 10 : first;
            variables.put("first", pageSize);
            if (afterCursor != null && !afterCursor.isEmpty()) { variables.put("after", afterCursor); }
            Map<String, Object> templateVars = prepareProjectsListVariables();
            Object orderBy = templateVars.get("orderBy");
            if (orderBy != null) { variables.put("orderBy", orderBy); }
            boolean haveRange = (startDateIso != null && !startDateIso.isEmpty()) || (endDateIso != null && !endDateIso.isEmpty());
            if (haveRange) {
                @SuppressWarnings("unchecked") Map<String, Object> filter = (Map<String, Object>) templateVars.get("filter");
                if (filter == null) filter = new HashMap<>();
                Map<String, Object> between = new HashMap<>();
                if (startDateIso != null && !startDateIso.isEmpty()) between.put("minDate", startDateIso + "T00:00:00.000Z");
                if (endDateIso != null && !endDateIso.isEmpty()) between.put("maxDate", endDateIso + "T23:59:59.000Z");
                Map<String, Object> dueDate = new HashMap<>();
                dueDate.put("between", between);
                filter.put("dueDate", dueDate);
                variables.put("filter", filter);
            } else {
                // Backend sometimes fails on completely absent filters; apply a wide default window
                @SuppressWarnings("unchecked") Map<String, Object> filter = (Map<String, Object>) templateVars.get("filter");
                if (filter == null) filter = new HashMap<>();
                Map<String, Object> between = new HashMap<>();
                between.put("minDate", "2000-01-01T00:00:00.000Z");
                between.put("maxDate", "2100-12-31T23:59:59.000Z");
                Map<String, Object> dueDate = new HashMap<>();
                dueDate.put("between", between);
                filter.put("dueDate", dueDate);
                variables.put("filter", filter);
            }

            Map<String, Object> graphqlRequest = new HashMap<>();
            graphqlRequest.put("query", query);
            graphqlRequest.put("variables", variables);
            try {
                System.out.println("[ListProjects] Variables => " + objectMapper.writeValueAsString(variables));
            } catch (JsonProcessingException ignore) {}

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(graphqlRequest, headers);
            ResponseEntity<String> response = exchangeWithRetry(config.getGraphqlUrl(), HttpMethod.POST, request);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Failed to list projects: " + response.getBody());
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.has("errors")) {
                JsonNode errors = root.get("errors");
                String message = errors.isArray() && errors.size() > 0 ? errors.get(0).get("message").asText() : "GraphQL error";
                // Print full GraphQL error to terminal for diagnostics
                System.out.println("[ListProjects] GraphQL errors => " + errors.toString());
                throw new RuntimeException("GraphQL error: " + message);
            }

            JsonNode conn = root.path("data").path("projectManagementProjects");
            Map<String, Object> result = new HashMap<>();
            if (conn.has("pageInfo")) {
                Map<String, Object> pageInfo = new HashMap<>();
                pageInfo.put("hasNextPage", conn.path("pageInfo").path("hasNextPage").asBoolean(false));
                pageInfo.put("endCursor", conn.path("pageInfo").path("endCursor").isMissingNode() || conn.path("pageInfo").path("endCursor").isNull() ? null : conn.path("pageInfo").path("endCursor").asText());
                result.put("pageInfo", pageInfo);
            }

            List<Map<String, Object>> nodes = new ArrayList<>();
            if (conn.has("edges") && conn.get("edges").isArray()) {
                for (JsonNode e : conn.get("edges")) {
                    JsonNode n = e.path("node");
                    Map<String, Object> p = new HashMap<>();
                    p.put("id", n.path("id").asText());
                    p.put("name", n.path("name").asText(null));
                    p.put("description", n.path("description").asText(null));
                    p.put("type", n.path("type").isMissingNode() ? null : n.path("type").asText(null));
                    p.put("status", n.path("status").asText(null));
                    p.put("startDate", n.path("startDate").asText(null));
                    p.put("dueDate", n.path("dueDate").asText(null));
                    p.put("completedDate", n.path("completedDate").isMissingNode() ? null : n.path("completedDate").asText(null));
                    p.put("priority", n.path("priority").isMissingNode() ? null : n.path("priority").asInt());
                    if (n.has("customer")) {
                        Map<String, Object> cust = new HashMap<>();
                        cust.put("id", n.path("customer").path("id").asText(null));
                        p.put("customer", cust);
                    }
                    if (n.has("assignee")) {
                        p.put("assigneeId", n.path("assignee").path("id").asText(null));
                    }
                    if (n.has("account")) {
                        p.put("accountId", n.path("account").path("id").asText(null));
                    }
                    if (n.has("addresses") && n.get("addresses").isArray()) {
                        List<Map<String, Object>> addrs = new ArrayList<>();
                        for (JsonNode a : n.get("addresses")) {
                            Map<String, Object> ad = new HashMap<>();
                            ad.put("streetAddressLine1", a.path("streetAddressLine1").asText(null));
                            ad.put("streetAddressLine2", a.path("streetAddressLine2").isMissingNode() || a.get("streetAddressLine2").isNull() ? null : a.get("streetAddressLine2").asText());
                            ad.put("streetAddressLine3", a.path("streetAddressLine3").isMissingNode() || a.get("streetAddressLine3").isNull() ? null : a.get("streetAddressLine3").asText());
                            ad.put("state", a.path("state").asText(null));
                            ad.put("postalCode", a.path("postalCode").asText(null));
                            addrs.add(ad);
                        }
                        p.put("addresses", addrs);
                    }
                    nodes.add(p);
                }
            }
            result.put("nodes", nodes);
            return result;
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401) {
                throw new RuntimeException("Unauthorized (401): Access token invalid or expired. Please reconnect to QuickBooks.");
            } else if (status == 403) {
                throw new RuntimeException("Forbidden (403): Missing scope 'project-management.project' or Projects not enabled.");
            }
            throw new RuntimeException("Failed to list projects: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("Failed to list projects: " + e.getMessage(), e);
        }
    }

    /**
     * Get a single project by ID via GraphQL.
     */
    public Map<String, Object> getProjectById(String accessToken, String realmId, String id) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }
        if (id == null || id.trim().isEmpty()) {
            throw new RuntimeException("Project ID is required");
        }

        try {
            String query = readResource("graphql/project_get.graphql");

            Map<String, Object> variables = new HashMap<>();
            variables.put("id", id);

            Map<String, Object> graphqlRequest = new HashMap<>();
            graphqlRequest.put("query", query);
            graphqlRequest.put("variables", variables);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(graphqlRequest, headers);
            ResponseEntity<String> response = exchangeWithRetry(config.getGraphqlUrl(), HttpMethod.POST, request);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Failed to get project: " + response.getBody());
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.has("errors")) {
                JsonNode errors = root.get("errors");
                String message = errors.isArray() && errors.size() > 0 ? errors.get(0).get("message").asText() : "GraphQL error";
                throw new RuntimeException("GraphQL error: " + message);
            }

            JsonNode n = root.path("data").path("projectManagementProject");
            if (n.isMissingNode() || n.isNull()) {
                throw new RuntimeException("Project not found: " + id);
            }
            Map<String, Object> p = new HashMap<>();
            // include id so downstream (invoice) has it available
            p.put("id", n.path("id").asText(id));
            p.put("name", n.path("name").asText(null));
            p.put("status", n.path("status").asText(null));
            p.put("description", n.path("description").asText(null));
            p.put("startDate", n.path("startDate").asText(null));
            p.put("dueDate", n.path("dueDate").asText(null));
            if (n.has("account")) { p.put("accountId", n.path("account").path("id").asText(null)); }
            if (n.has("customer")) {
                Map<String, Object> cust = new HashMap<>();
                cust.put("id", n.path("customer").path("id").asText(null));
                p.put("customer", cust);
            }
            return p;
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401) {
                throw new RuntimeException("Unauthorized (401): Access token invalid or expired. Please reconnect to QuickBooks.");
            } else if (status == 403) {
                throw new RuntimeException("Forbidden (403): Missing scope 'project-management.project' or Projects not enabled.");
            }
            throw new RuntimeException("Failed to get project: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("Failed to get project: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch multiple projects by IDs using GraphQL aliases in a single round-trip.
     * Returns a list of lightweight maps with common fields.
     */
    public java.util.List<java.util.Map<String, Object>> getProjectsByIds(String accessToken, String realmId, java.util.List<String> ids) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new RuntimeException("Access token is required");
        }
        if (realmId == null || realmId.trim().isEmpty()) {
            throw new RuntimeException("Realm ID is required");
        }
        if (ids == null || ids.isEmpty()) {
            throw new RuntimeException("At least one project ID is required");
        }

        // Cap to reasonable batch size
        int max = Math.min(ids.size(), 20);
        java.util.List<String> slice = ids.subList(0, max);

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("query Multi(");
            for (int i = 0; i < slice.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("$v").append(i + 1).append(": ID!");
            }
            sb.append(") {");
            for (int i = 0; i < slice.size(); i++) {
                sb.append(" p").append(i + 1).append(": projectManagementProject(id: $v").append(i + 1).append(") {");
                sb.append(" id name status description startDate dueDate ");
                sb.append(" account { id } customer { id } ");
                sb.append(" }");
            }
            sb.append(" }");

            java.util.Map<String, Object> variables = new java.util.HashMap<>();
            for (int i = 0; i < slice.size(); i++) {
                variables.put("v" + (i + 1), slice.get(i));
            }

            java.util.Map<String, Object> graphqlRequest = new java.util.HashMap<>();
            graphqlRequest.put("query", sb.toString());
            graphqlRequest.put("variables", variables);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");

            HttpEntity<java.util.Map<String, Object>> request = new HttpEntity<>(graphqlRequest, headers);
            ResponseEntity<String> response = exchangeWithRetry(config.getGraphqlUrl(), HttpMethod.POST, request);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Failed to get projects: " + response.getBody());
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.has("errors")) {
                JsonNode errors = root.get("errors");
                String message = errors.isArray() && errors.size() > 0 ? errors.get(0).get("message").asText() : "GraphQL error";
                throw new RuntimeException("GraphQL error: " + message);
            }

            JsonNode data = root.path("data");
            java.util.List<java.util.Map<String, Object>> results = new java.util.ArrayList<>();
            for (int i = 0; i < slice.size(); i++) {
                String alias = "p" + (i + 1);
                JsonNode n = data.path(alias);
                if (n.isMissingNode() || n.isNull()) {
                    // Not found; include stub with requested id
                    java.util.Map<String, Object> missing = new java.util.HashMap<>();
                    missing.put("id", slice.get(i));
                    missing.put("name", null);
                    missing.put("status", null);
                    results.add(missing);
                    continue;
                }
                java.util.Map<String, Object> p = new java.util.HashMap<>();
                p.put("id", n.path("id").asText(null));
                p.put("name", n.path("name").asText(null));
                p.put("status", n.path("status").asText(null));
                p.put("description", n.path("description").asText(null));
                p.put("startDate", n.path("startDate").asText(null));
                p.put("dueDate", n.path("dueDate").asText(null));
                if (n.has("account")) {
                    p.put("accountId", n.path("account").path("id").asText(null));
                }
                if (n.has("customer")) {
                    java.util.Map<String, Object> cust = new java.util.HashMap<>();
                    cust.put("id", n.path("customer").path("id").asText(null));
                    p.put("customer", cust);
                }
                results.add(p);
            }
            return results;
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Failed to get projects: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("Failed to get projects: " + e.getMessage(), e);
        }
    }
} 
