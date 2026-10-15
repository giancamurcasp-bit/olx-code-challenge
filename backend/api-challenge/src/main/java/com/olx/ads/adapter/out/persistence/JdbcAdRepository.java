package com.olx.ads.adapter.out.persistence;

import com.olx.ads.application.port.AdRepository;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.AdStatus;
import com.olx.ads.domain.Category;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdRepository implements AdRepository {

  private final JdbcClient jdbc;

  public JdbcAdRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Ad insert(Ad ad) {
    return jdbc.sql(
            """
            INSERT INTO ads (title, description, price, category, images, status,
                             moderation_attempts, next_attempt_at, created_at, updated_at)
            VALUES (:title, :description, :price, :category, :images, :status,
                    :attempts, :nextAttemptAt, :createdAt, :updatedAt)
            RETURNING *
            """)
        .param("title", ad.getTitle())
        .param("description", ad.getDescription())
        .param("price", ad.getPrice())
        .param("category", ad.getCategory().value())
        .param("images", ad.getImages().toArray(String[]::new))
        .param("status", ad.getStatus().value())
        .param("attempts", ad.getModerationAttempts())
        .param("nextAttemptAt", toTimestamp(ad.getNextAttemptAt()))
        .param("createdAt", toTimestamp(ad.getCreatedAt()))
        .param("updatedAt", toTimestamp(ad.getUpdatedAt()))
        .query(JdbcAdRepository::toAd)
        .single();
  }

  @Override
  public Optional<Ad> insertWithIdempotencyKey(Ad ad, String idempotencyKey) {
    throw new UnsupportedOperationException("not implemented yet");
  }

  @Override
  public Optional<Ad> findByIdempotencyKey(String idempotencyKey) {
    throw new UnsupportedOperationException("not implemented yet");
  }

  @Override
  public Optional<Ad> findById(long id) {
    return jdbc.sql("SELECT * FROM ads WHERE id = :id")
        .param("id", id)
        .query(JdbcAdRepository::toAd)
        .optional();
  }

  /**
   * One statement, so the claim is atomic without an explicit transaction. {@code SKIP LOCKED} lets
   * concurrent workers claim different ads instead of waiting on each other.
   */
  @Override
  public List<Ad> claimDue(int limit, Instant now, Duration lease) {
    return jdbc.sql(
            """
            UPDATE ads SET next_attempt_at = :leaseUntil
            WHERE id IN (SELECT id FROM ads
                         WHERE status = 'pending' AND next_attempt_at <= :now
                         ORDER BY next_attempt_at
                         LIMIT :limit
                         FOR UPDATE SKIP LOCKED)
            RETURNING *
            """)
        .param("leaseUntil", toTimestamp(now.plus(lease)))
        .param("now", toTimestamp(now))
        .param("limit", limit)
        .query(JdbcAdRepository::toAd)
        .list();
  }

  @Override
  public boolean update(Ad ad, Instant leasedUntil) {
    int updated =
        jdbc.sql(
                """
                UPDATE ads SET status = :status,
                               rejection_reason = :rejectionReason,
                               moderation_attempts = :attempts,
                               next_attempt_at = :nextAttemptAt,
                               last_moderation_error = :lastError,
                               updated_at = :updatedAt
                WHERE id = :id AND status = 'pending' AND next_attempt_at = :leasedUntil
                """)
            .param("status", ad.getStatus().value())
            .param("rejectionReason", ad.getRejectionReason())
            .param("attempts", ad.getModerationAttempts())
            .param("nextAttemptAt", toTimestamp(ad.getNextAttemptAt()))
            .param("lastError", ad.getLastModerationError())
            .param("updatedAt", toTimestamp(ad.getUpdatedAt()))
            .param("id", ad.getId())
            .param("leasedUntil", toTimestamp(leasedUntil))
            .update();
    return updated == 1;
  }

  private static Ad toAd(ResultSet row, int rowNum) throws SQLException {
    return Ad.restore(
        row.getLong("id"),
        row.getString("title"),
        row.getString("description"),
        row.getBigDecimal("price"),
        Category.fromValue(row.getString("category")),
        List.of((String[]) row.getArray("images").getArray()),
        AdStatus.fromValue(row.getString("status")),
        row.getString("rejection_reason"),
        row.getInt("moderation_attempts"),
        toInstant(row, "next_attempt_at"),
        row.getString("last_moderation_error"),
        toInstant(row, "created_at"),
        toInstant(row, "updated_at"));
  }

  // The PostgreSQL driver maps TIMESTAMPTZ to OffsetDateTime, not Instant.
  private static OffsetDateTime toTimestamp(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant toInstant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
