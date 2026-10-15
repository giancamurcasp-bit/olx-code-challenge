package com.olx.ads.adapter.in.scheduler;

import com.olx.ads.application.ModerationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls for due ads. Disabled with {@code moderation.worker.enabled=false} (e.g. in tests). */
@Component
@ConditionalOnProperty(name = "moderation.worker.enabled", havingValue = "true")
class ModerationWorker {

  private final ModerationService moderationService;

  ModerationWorker(ModerationService moderationService) {
    this.moderationService = moderationService;
  }

  @Scheduled(fixedDelayString = "${moderation.worker.poll-interval}")
  void run() {
    moderationService.moderateDueAds();
  }
}
