package com.olx.ads.application.port;

import com.olx.ads.domain.Ad;
import com.olx.ads.domain.ModerationResult;

/** Port to the external moderation provider. Never throws: every failure is a result. */
public interface ModerationGateway {

  ModerationResult check(Ad ad);
}
