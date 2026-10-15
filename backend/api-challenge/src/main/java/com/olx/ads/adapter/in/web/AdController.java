package com.olx.ads.adapter.in.web;

import com.olx.ads.application.AdService;
import com.olx.ads.application.AdService.AdPage;
import com.olx.ads.application.AdService.Creation;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.AdStatus;
import com.olx.ads.domain.Category;
import com.olx.ads.domain.InvalidAdException;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/ads")
class AdController {

  /** Visible ASCII, like Stripe's keys; UUIDs fit. */
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

  private static final int MAX_PAGE_SIZE = 50;

  private final AdService adService;

  AdController(AdService adService) {
    this.adService = adService;
  }

  /**
   * Stores the ad as {@code pending}; moderation happens asynchronously. The {@code
   * Idempotency-Key} is required, so a retry returns the original ad (marked with {@code
   * Idempotent-Replayed}) instead of creating a duplicate.
   */
  @PostMapping
  ResponseEntity<AdResponse> create(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CreateAdRequest request) {
    if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1 to 255 visible ASCII characters");
    }
    Creation creation = adService.create(request.toNewAd(), idempotencyKey);
    Ad ad = creation.ad();
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(ad.getId())
            .toUri();
    var response = ResponseEntity.created(location);
    if (creation.replayed()) {
      response.header("Idempotent-Replayed", "true");
    }
    return response.body(AdResponse.from(ad));
  }

  /** Lists ads newest first, {@code published} by default (DECISIONS.md, section 4). */
  @GetMapping
  AdPageResponse list(
      @RequestParam(defaultValue = "published") String status,
      @RequestParam(required = false) String category,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String cursor) {
    if (limit < 1 || limit > MAX_PAGE_SIZE) {
      throw new InvalidParameterException(
          "limit", "must be between 1 and %d".formatted(MAX_PAGE_SIZE));
    }
    AdPage page =
        adService.list(
            parseStatus(status),
            category == null ? null : parseCategory(category),
            cursor == null ? null : CursorCodec.decode(cursor),
            limit);
    return new AdPageResponse(
        page.ads().stream().map(AdResponse::from).toList(),
        page.next() == null ? null : CursorCodec.encode(page.next()));
  }

  private static AdStatus parseStatus(String status) {
    try {
      return AdStatus.fromValue(status);
    } catch (IllegalArgumentException e) {
      throw new InvalidParameterException(
          "status",
          "must be one of: "
              + Arrays.stream(AdStatus.values())
                  .map(AdStatus::value)
                  .collect(Collectors.joining(", ")));
    }
  }

  private static Category parseCategory(String category) {
    try {
      return Category.fromValue(category);
    } catch (InvalidAdException e) {
      throw new InvalidParameterException("category", e.getMessage());
    }
  }

  /** Returns the ad in any status, including the moderation outcome. */
  @GetMapping("/{id}")
  AdResponse get(@PathVariable long id) {
    return AdResponse.from(adService.get(id));
  }
}
