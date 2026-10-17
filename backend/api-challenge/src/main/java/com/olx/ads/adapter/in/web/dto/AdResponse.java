package com.olx.ads.adapter.in.web.dto;

import com.olx.ads.domain.Ad;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** An ad as exposed by the API. Field names are serialized in snake_case. */
public record AdResponse(
    long id,
    String title,
    String description,
    BigDecimal price,
    String category,
    List<String> images,
    String status,
    String rejectionReason,
    int moderationAttempts,
    String lastModerationError,
    Instant createdAt,
    Instant updatedAt) {

  public static AdResponse from(Ad ad) {
    return new AdResponse(
        ad.getId(),
        ad.getTitle(),
        ad.getDescription(),
        ad.getPrice(),
        ad.getCategory().value(),
        ad.getImages(),
        ad.getStatus().value(),
        ad.getRejectionReason(),
        ad.getModerationAttempts(),
        ad.getLastModerationError(),
        ad.getCreatedAt(),
        ad.getUpdatedAt());
  }
}
