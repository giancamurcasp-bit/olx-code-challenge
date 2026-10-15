package com.olx.ads.domain;

import java.util.Locale;

/** Lifecycle of an ad (DECISIONS.md, section 2). */
public enum AdStatus {
  PENDING,
  PUBLISHED,
  REJECTED,
  MODERATION_FAILED;

  /** The identifier used in the API and the database. */
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static AdStatus fromValue(String value) {
    return valueOf(value.toUpperCase(Locale.ROOT));
  }
}
