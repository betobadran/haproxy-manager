package dev.hapm.manager;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hapm")
public record HapmProperties(
    String adminUser,
    String adminPassword,
    String secret,
    String acmeDirectory,
    String haproxyBin,
    int nodeOfflineAfterSec,
    int deploymentTimeoutSec,
    int certRenewBeforeDays) {}
