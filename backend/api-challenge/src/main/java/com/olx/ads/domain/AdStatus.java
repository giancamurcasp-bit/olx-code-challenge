package com.olx.ads.domain;

/** Lifecycle of an ad (DECISIONS.md, section 2). */
public enum AdStatus {
  PENDING,
  PUBLISHED,
  REJECTED,
  MODERATION_FAILED
}
