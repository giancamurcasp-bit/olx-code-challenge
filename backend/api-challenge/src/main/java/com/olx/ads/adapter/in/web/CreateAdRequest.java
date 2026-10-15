package com.olx.ads.adapter.in.web;

import com.olx.ads.application.AdService.NewAd;
import com.olx.ads.domain.Category;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for {@code POST /ads}. Bean Validation only checks presence and simple bounds; the
 * precise rules (title length in characters, price scale, image URLs, category) live in the domain,
 * so they are defined once and apply to every way an ad is created.
 */
record CreateAdRequest(
    @NotBlank String title,
    @NotBlank String description,
    @NotNull @Positive BigDecimal price,
    @NotBlank String category,
    @NotEmpty @Size(max = 10) List<String> images) {

  NewAd toNewAd() {
    return new NewAd(title, description, price, Category.fromValue(category), images);
  }
}
