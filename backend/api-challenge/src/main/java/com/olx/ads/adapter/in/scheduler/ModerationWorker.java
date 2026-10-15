package com.olx.ads.adapter.in.scheduler;

import com.olx.ads.application.ModerationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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

  /**
   * Runs on one instance at a time: the others skip the run while the lock is held (DECISIONS.md,
   * section 3), so the in-memory rate limiter is effectively global.
   */
  @Scheduled(
      fixedDelayString = "${moderation.worker.poll-interval}",
      initialDelayString = "${moderation.worker.poll-interval}") // not mid-startup, e.g. in tests
  @SchedulerLock(name = "moderation-worker")
  void run() {
    moderationService.moderateDueAds();
  }
}
