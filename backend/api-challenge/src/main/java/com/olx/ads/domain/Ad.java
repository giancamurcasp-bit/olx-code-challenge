package com.olx.ads.domain;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;

/** A marketplace ad and its moderation lifecycle (DECISIONS.md, sections 2 and 5). */
public class Ad {

  static final int MIN_TITLE_LENGTH = 10;
  static final int MAX_TITLE_LENGTH = 100;
  static final int MAX_DESCRIPTION_LENGTH = 5000;
  static final int MAX_IMAGES = 10;
  static final int MAX_IMAGE_URL_LENGTH = 2048;
  static final int MAX_PRICE_SCALE = 2;
  static final int MAX_PRICE_INTEGER_DIGITS = 10; // NUMERIC(12,2)
  static final String RETRIES_EXHAUSTED = "retries_exhausted";

  private Long id;
  private final String title;
  private final String description;
  private final BigDecimal price;
  private final Category category;
  private final List<String> images;
  private AdStatus status;
  private String rejectionReason;
  private int moderationAttempts;
  private Instant nextAttemptAt;
  private String lastModerationError;
  private final Instant createdAt;
  private Instant updatedAt;

  private Ad(
      Long id,
      String title,
      String description,
      BigDecimal price,
      Category category,
      List<String> images,
      AdStatus status,
      String rejectionReason,
      int moderationAttempts,
      Instant nextAttemptAt,
      String lastModerationError,
      Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.title = title;
    this.description = description;
    this.price = price;
    this.category = category;
    this.images = images;
    this.status = status;
    this.rejectionReason = rejectionReason;
    this.moderationAttempts = moderationAttempts;
    this.nextAttemptAt = nextAttemptAt;
    this.lastModerationError = lastModerationError;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  /** Creates a new ad, pending and due for moderation immediately. */
  public static Ad create(
      String title,
      String description,
      BigDecimal price,
      Category category,
      List<String> images,
      Instant now) {
    String trimmedTitle = validTitle(title);
    validateDescription(description);
    validatePrice(price);
    require(category != null, "category", "is required");
    validateImages(images);
    return new Ad(
        null,
        trimmedTitle,
        description,
        price,
        category,
        List.copyOf(images),
        AdStatus.PENDING,
        null,
        0,
        now,
        null,
        now,
        now);
  }

  /**
   * Rebuilds an ad that already exists, e.g. when loading it from storage. Creation rules are not
   * re-applied: the ad was valid when created.
   */
  public static Ad restore(
      long id,
      String title,
      String description,
      BigDecimal price,
      Category category,
      List<String> images,
      AdStatus status,
      String rejectionReason,
      int moderationAttempts,
      Instant nextAttemptAt,
      String lastModerationError,
      Instant createdAt,
      Instant updatedAt) {
    return new Ad(
        id,
        title,
        description,
        price,
        category,
        List.copyOf(images),
        status,
        rejectionReason,
        moderationAttempts,
        nextAttemptAt,
        lastModerationError,
        createdAt,
        updatedAt);
  }

  /**
   * Applies the result of one moderation call. Only pending ads can be moderated: a finished ad
   * never changes status again, so a duplicate or late result can't overwrite it.
   */
  public void applyModeration(ModerationResult result, RetryPolicy policy, Instant now) {
    if (status != AdStatus.PENDING) {
      throw new IllegalStateException("ad " + id + " is already " + status);
    }
    switch (result) {
      case ModerationResult.Approved approved -> {
        moderationAttempts++;
        finish(AdStatus.PUBLISHED);
      }
      case ModerationResult.Rejected rejected -> {
        moderationAttempts++;
        rejectionReason = rejected.reason();
        finish(AdStatus.REJECTED);
      }
      case ModerationResult.PermanentFailure failure -> {
        moderationAttempts++;
        failModeration(failure.error());
      }
      case ModerationResult.TransientFailure failure -> {
        moderationAttempts++;
        if (moderationAttempts >= policy.maxAttempts()) {
          failModeration(RETRIES_EXHAUSTED);
        } else {
          lastModerationError = failure.error();
          nextAttemptAt = now.plus(policy.delayFor(moderationAttempts));
        }
      }
      case ModerationResult.RateLimited rateLimited ->
          nextAttemptAt = now.plus(rateLimited.retryAfter());
    }
    updatedAt = now;
  }

  /** Whether {@code other} describes the same ad content, ignoring ids, status and timestamps. */
  public boolean hasSameContentAs(Ad other) {
    return title.equals(other.title)
        && description.equals(other.description)
        && price.compareTo(other.price) == 0
        && category == other.category
        && images.equals(other.images);
  }

  private void failModeration(String error) {
    lastModerationError = error;
    finish(AdStatus.MODERATION_FAILED);
  }

  private void finish(AdStatus finalStatus) {
    status = finalStatus;
    nextAttemptAt = null;
  }

  // --- accessors (explicit: the domain doesn't depend on Lombok, see ArchitectureTest) ---

  public Long getId() {
    return id;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public Category getCategory() {
    return category;
  }

  public List<String> getImages() {
    return images;
  }

  public AdStatus getStatus() {
    return status;
  }

  public String getRejectionReason() {
    return rejectionReason;
  }

  public int getModerationAttempts() {
    return moderationAttempts;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public String getLastModerationError() {
    return lastModerationError;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  // --- validation ---

  private static String validTitle(String title) {
    require(title != null, "title", "is required");
    String trimmed = title.strip();
    int length = trimmed.codePointCount(0, trimmed.length());
    require(
        length >= MIN_TITLE_LENGTH && length <= MAX_TITLE_LENGTH,
        "title",
        "must have between %d and %d characters".formatted(MIN_TITLE_LENGTH, MAX_TITLE_LENGTH));
    requireNoControlCharacters(trimmed, "title");
    return trimmed;
  }

  private static void validateDescription(String description) {
    require(description != null && !description.isBlank(), "description", "is required");
    require(
        description.codePointCount(0, description.length()) <= MAX_DESCRIPTION_LENGTH,
        "description",
        "must have at most %d characters".formatted(MAX_DESCRIPTION_LENGTH));
    requireNoControlCharacters(description, "description");
  }

  /** PostgreSQL can't store NUL, and the other control characters are never meaningful text. */
  private static void requireNoControlCharacters(String text, String field) {
    boolean hasControl =
        text.codePoints()
            .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t');
    require(!hasControl, field, "must not contain control characters");
  }

  private static void validatePrice(BigDecimal price) {
    require(price != null, "price", "is required");
    require(price.signum() > 0, "price", "must be greater than zero");
    require(
        price.stripTrailingZeros().scale() <= MAX_PRICE_SCALE,
        "price",
        "must have at most %d decimal places".formatted(MAX_PRICE_SCALE));
    require(price.precision() - price.scale() <= MAX_PRICE_INTEGER_DIGITS, "price", "is too large");
  }

  private static void validateImages(List<String> images) {
    require(
        images != null && !images.isEmpty() && images.size() <= MAX_IMAGES,
        "images",
        "must have between 1 and %d images".formatted(MAX_IMAGES));
    images.forEach(
        image -> {
          require(isAbsoluteHttpUrl(image), "images", "must be absolute http(s) URLs");
          require(
              image.length() <= MAX_IMAGE_URL_LENGTH,
              "images",
              "must have at most %d characters".formatted(MAX_IMAGE_URL_LENGTH));
        });
  }

  private static boolean isAbsoluteHttpUrl(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme();
      return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
          && uri.getHost() != null;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static void require(boolean condition, String field, String message) {
    if (!condition) {
      throw new InvalidAdException(field, message);
    }
  }
}
