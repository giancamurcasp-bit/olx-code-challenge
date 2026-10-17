package com.olx.ads.adapter.in.web;

import com.olx.ads.adapter.in.web.error.InvalidParameterException;
import com.olx.ads.application.port.PageCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Turns a {@link PageCursor} into the opaque {@code next_cursor} string and back. Opaque so clients
 * don't build cursors themselves and the format can change (DECISIONS.md, section 4).
 */
public final class CursorCodec {

  private CursorCodec() {}

  public static String encode(PageCursor cursor) {
    String raw = cursor.createdAt() + "," + cursor.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static PageCursor decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = raw.split(",", -1);
      if (parts.length != 2) {
        throw new IllegalArgumentException("expected 2 parts");
      }
      return new PageCursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
    } catch (RuntimeException e) { // bad Base64, date or number: all mean a cursor we didn't issue
      throw new InvalidParameterException("cursor", "is not a valid cursor");
    }
  }
}
