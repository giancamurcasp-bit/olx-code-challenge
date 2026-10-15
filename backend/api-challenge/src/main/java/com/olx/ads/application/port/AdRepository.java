package com.olx.ads.application.port;

import com.olx.ads.domain.Ad;
import java.util.Optional;

/** Persistence port for ads. */
public interface AdRepository {

  /** Stores a new ad and returns it as stored, with its generated id. */
  Ad insert(Ad ad);

  Optional<Ad> findById(long id);
}
