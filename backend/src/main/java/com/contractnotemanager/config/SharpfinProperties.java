package com.contractnotemanager.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection to the Sharpfin API. Credentials come from the environment
 * (SHARPFIN_USERNAME / SHARPFIN_PASSWORD) and are never logged.
 */
@ConfigurationProperties("sharpfin")
public record SharpfinProperties(
        String baseUrl,
        @DefaultValue("STAGE") String environmentLabel,
        String username,
        String password,
        Login login,
        @DefaultValue("100") int pageSize,
        @DefaultValue("30s") Duration timeout) {

    public record Login(
            @DefaultValue("/api/sessions") String path,
            @DefaultValue("POST") String method,
            @DefaultValue("username") String usernameField,
            @DefaultValue("password") String passwordField,
            String tokenField) {
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    @Override
    public String toString() {
        return "SharpfinProperties[baseUrl=" + baseUrl + ", username=" + username + ", password=***]";
    }
}
