package com.quickbooks.demo.service.client;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.intuit.ipp.core.Context;
import com.intuit.ipp.core.ServiceType;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.security.OAuth2Authorizer;
import com.intuit.ipp.services.DataService;
import com.quickbooks.demo.config.QuickBooksConfig;
import com.quickbooks.demo.model.QuickBooksContext;

/**
 * Factory for QuickBooks Java SDK DataService instances.
 */
@Component
public class QuickBooksSdkClient {

    @Autowired
    private QuickBooksConfig config;

    public DataService dataService(QuickBooksContext ctx) {
        try {
            OAuth2Authorizer oauth2Authorizer = new OAuth2Authorizer(ctx.rawToken());
            Context context = new Context(oauth2Authorizer, ServiceType.QBO, ctx.realmId());
            if (config.getMinorVersion() != null && !config.getMinorVersion().trim().isEmpty()) {
                context.setMinorVersion(config.getMinorVersion().trim());
            }
            return new DataService(context);
        } catch (FMSException e) {
            throw new RuntimeException("Failed to create QuickBooks DataService: " + e.getMessage(), e);
        }
    }
}
