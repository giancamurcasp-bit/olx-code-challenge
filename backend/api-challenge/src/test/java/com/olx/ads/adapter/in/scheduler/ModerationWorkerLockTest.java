package com.olx.ads.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.olx.ads.TestcontainersConfiguration;
import com.olx.ads.application.port.AdRepository;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.Category;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Only one instance runs the worker at a time (DECISIONS.md, section 3): the lock lives in
 * PostgreSQL, so "another instance" is simulated by writing its lock row.
 */
@SpringBootTest(
    properties = {
      "moderation.worker.enabled=true",
      "moderation.worker.poll-interval=1h", // runs are triggered by the test, not the timer
      "moderation.base-url=http://localhost:1" // unreachable: a run that calls it counts an attempt
    })
@Import(TestcontainersConfiguration.class)
class ModerationWorkerLockTest {

  @Autowired ModerationWorker worker;
  @Autowired AdRepository repository;
  @Autowired JdbcClient jdbc;

  private Ad ad;

  @BeforeEach
  void setUp() {
    jdbc.sql("TRUNCATE ads, shedlock").update();
    ad = repository.insert(dueAd());
  }

  @Test
  void skipsTheRunWhileAnotherInstanceHoldsTheLock() {
    jdbc.sql(
            """
            INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
            VALUES ('moderation-worker', timezone('utc', now()) + interval '1 hour',
                    timezone('utc', now()), 'other-instance')
            """)
        .update();

    worker.run();

    Ad found = repository.findById(ad.getId()).orElseThrow();
    assertThat(found.getModerationAttempts()).isZero();
    assertThat(found.getNextAttemptAt()).isEqualTo(ad.getNextAttemptAt()); // not even claimed
  }

  @Test
  void runsAndReleasesTheLockWhenNoOtherInstanceHoldsIt() {
    worker.run();

    assertThat(repository.findById(ad.getId()).orElseThrow().getModerationAttempts()).isOne();
    boolean released =
        jdbc.sql(
                """
                SELECT lock_until <= timezone('utc', now()) FROM shedlock
                WHERE name = 'moderation-worker'
                """)
            .query(Boolean.class)
            .single();
    assertThat(released).isTrue();
  }

  private static Ad dueAd() {
    return Ad.create(
        "Sofá 3 lugares retrátil",
        "Descrição.",
        new BigDecimal("900.00"),
        Category.MOVEIS,
        List.of("https://example.com/a.jpg"),
        Instant.now().minus(1, ChronoUnit.MINUTES));
  }
}
