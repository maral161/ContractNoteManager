package com.contractnotemanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Claude API settings used to read contract-note PDFs. */
@ConfigurationProperties("claude")
public record ClaudeProperties(
        String apiKey,
        @DefaultValue("claude-opus-5-5") String model,
        @DefaultValue("8000") long maxTokens) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "ClaudeProperties[model=" + model + ", apiKey=***]";
    }
}
