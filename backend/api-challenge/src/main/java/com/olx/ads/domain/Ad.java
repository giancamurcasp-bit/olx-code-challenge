package com.olx.ads.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.Getter;

/** A marketplace ad and its moderation lifecycle. */
@Getter
public class Ad {

  private Long id;
  private String title;
  private String description;
  private BigDecimal price;
  private Category category;
  private List<String> images;
  private AdStatus status;
  private String rejectionReason;
  private int moderationAttempts;
  private Instant nextAttemptAt;
  private String lastModerationError;
  private Instant createdAt;
  private Instant updatedAt;

  public static Ad create(
      String title,
      String description,
      BigDecimal price,
      Category category,
      List<String> images,
      Instant now) {
    throw new UnsupportedOperationException("not implemented yet");
  }

  public void applyModeration(ModerationResult result, RetryPolicy policy, Instant now) {
    throw new UnsupportedOperationException("not implemented yet");
  }
}
