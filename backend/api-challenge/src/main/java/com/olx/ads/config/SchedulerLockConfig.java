package com.olx.ads.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lets only one instance run the moderation worker at a time, with the lock in PostgreSQL
 * (DECISIONS.md, section 3). Lock times come from the database clock, so clock skew between
 * instances can't let two of them hold it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "moderation.worker.enabled", havingValue = "true")
@EnableSchedulerLock(defaultLockAtMostFor = "${moderation.worker.lock-at-most-for}")
class SchedulerLockConfig {

  @Bean
  LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .usingDbTime()
            .build());
  }
}
