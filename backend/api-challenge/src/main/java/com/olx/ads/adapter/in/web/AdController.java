package com.olx.ads.adapter.in.web;

import com.olx.ads.application.AdService;
import com.olx.ads.application.AdService.Creation;
import com.olx.ads.domain.Ad;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/ads")
class AdController {

  /** Visible ASCII, like Stripe's keys; UUIDs fit. */
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

  private final AdService adService;

  AdController(AdService adService) {
    this.adService = adService;
  }

  /**
   * Stores the ad as {@code pending}; moderation happens asynchronously. A retry with the same
   * {@code Idempotency-Key} returns the original ad, marked with {@code Idempotent-Replayed}.
   */
  @PostMapping
  ResponseEntity<AdResponse> create(
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody CreateAdRequest request) {
    if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
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

  /** Returns the ad in any status, including the moderation outcome. */
  @GetMapping("/{id}")
  AdResponse get(@PathVariable long id) {
    return AdResponse.from(adService.get(id));
  }
}
