package com.olx.ads.adapter.in.web;

import com.olx.ads.application.AdService;
import com.olx.ads.domain.Ad;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/ads")
class AdController {

  private final AdService adService;

  AdController(AdService adService) {
    this.adService = adService;
  }

  /** Stores the ad as {@code pending}; moderation happens asynchronously. */
  @PostMapping
  ResponseEntity<AdResponse> create(@Valid @RequestBody CreateAdRequest request) {
    Ad ad = adService.create(request.toNewAd());
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(ad.getId())
            .toUri();
    return ResponseEntity.created(location).body(AdResponse.from(ad));
  }

  /** Returns the ad in any status, including the moderation outcome. */
  @GetMapping("/{id}")
  AdResponse get(@PathVariable long id) {
    return AdResponse.from(adService.get(id));
  }
}
