package com.contractnotemanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Display settings for the single local user. */
@ConfigurationProperties("app")
public record AppProperties(String userName, String userRole) {
}
