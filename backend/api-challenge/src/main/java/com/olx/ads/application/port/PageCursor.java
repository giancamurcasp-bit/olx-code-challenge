package com.olx.ads.application.port;

import java.time.Instant;

/**
 * Position in the listing, ordered by {@code (created_at DESC, id DESC)}: the next page starts
 * right after the ad with this {@code createdAt} and {@code id} (DECISIONS.md, section 4).
 */
public record PageCursor(Instant createdAt, long id) {}
