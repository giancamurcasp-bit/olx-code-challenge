package com.olx.ads.adapter.out.persistence;

import com.olx.ads.application.port.AdRepository;
import com.olx.ads.domain.Ad;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdRepository implements AdRepository {

  @Override
  public Ad insert(Ad ad) {
    throw new UnsupportedOperationException("not implemented yet");
  }
}
