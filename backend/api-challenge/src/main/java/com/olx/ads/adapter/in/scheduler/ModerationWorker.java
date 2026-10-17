package com.olx.ads.adapter.in.scheduler;

import com.olx.ads.application.ModerationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for due ads. Runs only where {@code moderation.worker.enabled} is true: the worker
 * deployment, one replica, separate from the API (DECISIONS.md, section 3).
 */
@Component
@ConditionalOnProperty(name = "moderation.worker.enabled", havingValue = "true")
class ModerationWorker {

  private final ModerationService moderationService;

  ModerationWorker(ModerationService moderationService) {
    this.moderationService = moderationService;
  }

  @Scheduled(
      fixedDelayString = "${moderation.worker.poll-interval}",
      initialDelayString = "${moderation.worker.poll-interval}") // not mid-startup, e.g. in tests
  void run() {
    moderationService.moderateDueAds();
  }
}
