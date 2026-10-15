package com.olx.ads.application.port;

import com.olx.ads.domain.Ad;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for ads. */
public interface AdRepository {

  /** Stores a new ad and returns it as stored, with its generated id. */
  Ad insert(Ad ad);

  Optional<Ad> findById(long id);

  /**
   * Atomically claims up to {@code limit} pending ads that are due at {@code now}, leasing them
   * until {@code now + lease} so other workers skip them. If the worker dies, the lease expires and
   * the ads become due again.
   */
  List<Ad> claimDue(int limit, Instant now, Duration lease);

  /**
   * Saves the moderation state of an ad that is still pending in storage.
   *
   * @return false if the ad was no longer pending (e.g. a duplicate worker already finished it)
   */
  boolean update(Ad ad);
}
