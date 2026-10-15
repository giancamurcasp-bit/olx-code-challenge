package com.olx.ads.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("moderation.worker")
public record ModerationWorkerProperties(
    boolean enabled, Duration pollInterval, int batchSize, Duration lease, int concurrency) {}
