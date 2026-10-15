package com.olx.ads.adapter.out.moderation;

import com.olx.ads.application.port.ModerationGateway;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.ModerationResult;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpModerationGateway implements ModerationGateway {

  public HttpModerationGateway(RestClient.Builder builder, ModerationProperties properties) {}

  @Override
  public ModerationResult check(Ad ad) {
    throw new UnsupportedOperationException("not implemented yet");
  }
}
