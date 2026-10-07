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
        /** Value sent as date_type for each choice; adjust here if Sharpfin uses other names. */
        @DefaultValue DateTypes dateTypes,
        @DefaultValue("10") int pageSize,
        @DefaultValue("30s") Duration timeout) {

    public record Login(
            @DefaultValue("/api/sessions") String path,
            @DefaultValue("POST") String method,
            @DefaultValue("email") String usernameField,
            @DefaultValue("password") String passwordField,
            String tokenField) {
    }

    public record DateTypes(
            @DefaultValue("booked") String booked,
            @DefaultValue("traded") String traded,
            @DefaultValue("settled") String settled) {
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    @Override
    public String toString() {
        return "SharpfinProperties[baseUrl=" + baseUrl + ", username=" + username + ", password=***]";
    }
}
