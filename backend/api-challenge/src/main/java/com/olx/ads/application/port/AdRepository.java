package com.olx.ads.application.port;

import com.olx.ads.domain.Ad;
import com.olx.ads.domain.AdStatus;
import com.olx.ads.domain.Category;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for ads. */
public interface AdRepository {

  /** Stores a new ad and returns it as stored, with its generated id. */
  Ad insert(Ad ad);

  /**
   * Stores a new ad under a client-supplied idempotency key, atomically: if an ad was already
   * stored with that key (even by a concurrent request), nothing is stored and the result is empty.
   */
  Optional<Ad> insertWithIdempotencyKey(Ad ad, String idempotencyKey);

  Optional<Ad> findById(long id);

  Optional<Ad> findByIdempotencyKey(String idempotencyKey);

  /**
   * Returns up to {@code limit} ads with the given status, newest first ({@code created_at DESC, id
   * DESC}).
   *
   * @param category optional; null lists every category
   * @param after optional; null starts from the newest ad
   */
  List<Ad> findPage(AdStatus status, Category category, PageCursor after, int limit);

  /**
   * Atomically claims up to {@code limit} pending ads that are due at {@code now}, leasing them
   * until {@code now + lease} so other workers skip them. If the worker dies, the lease expires and
   * the ads become due again.
   */
  List<Ad> claimDue(int limit, Instant now, Duration lease);

  /**
   * Saves the moderation state of an ad, only if it is still pending and still leased until {@code
   * leasedUntil} (the value its claim returned as {@code next_attempt_at}). The lease acts as a
   * fencing token: if it expired and another worker claimed the ad, this stale write is discarded.
   *
   * @return false if the write was discarded
   */
  boolean update(Ad ad, Instant leasedUntil);
}
