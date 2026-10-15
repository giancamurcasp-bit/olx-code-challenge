package com.olx.ads.adapter.out.moderation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("moderation")
public record ModerationProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {}
