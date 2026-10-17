package com.olx.ads.adapter.in.web.controller;

import com.olx.ads.adapter.in.web.CursorCodec;
import com.olx.ads.adapter.in.web.dto.AdPageResponse;
import com.olx.ads.adapter.in.web.dto.AdResponse;
import com.olx.ads.adapter.in.web.dto.CreateAdRequest;
import com.olx.ads.adapter.in.web.error.InvalidParameterException;
import com.olx.ads.application.AdService;
import com.olx.ads.application.AdService.AdPage;
import com.olx.ads.application.AdService.Creation;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.AdStatus;
import com.olx.ads.domain.Category;
import com.olx.ads.domain.InvalidAdException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
  @Operation(summary = "Create an ad; it is moderated in the background")
  @ApiResponse(
      responseCode = "201",
      description = "Ad stored as pending, or the original ad on a replayed Idempotency-Key",
      headers = {
        @Header(name = "Location", description = "URL of the ad"),
        @Header(name = "Idempotent-Replayed", description = "`true` when the ad already existed")
      })
  @ApiResponse(
      responseCode = "400",
      description = "Invalid ad, or a missing or malformed Idempotency-Key",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "422",
      description = "Idempotency-Key already used for a different ad",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  ResponseEntity<AdResponse> create(
      @Parameter(description = "Unique per ad (e.g. a UUID), reused on retries")
          @RequestHeader("Idempotency-Key")
          String idempotencyKey,
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
  @Operation(summary = "List ads, newest first, with cursor pagination")
  @ApiResponse(responseCode = "200", description = "One page; `next_cursor` is null on the last")
  @ApiResponse(
      responseCode = "400",
      description = "Invalid query parameter",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  AdPageResponse list(
      @Parameter(
              description = "Ad status",
              schema =
                  @Schema(
                      allowableValues = {"pending", "published", "rejected", "moderation_failed"}))
          @RequestParam(defaultValue = "published")
          String status,
      @Parameter(
              description = "Category; all when omitted",
              schema =
                  @Schema(
                      allowableValues = {
                        "eletronicos",
                        "moveis",
                        "veiculos",
                        "imoveis",
                        "esportes",
                        "colecionaveis"
                      }))
          @RequestParam(required = false)
          String category,
      @Parameter(description = "Page size, 1 to 50") @RequestParam(defaultValue = "20") int limit,
      @Parameter(description = "`next_cursor` of the previous page") @RequestParam(required = false)
          String cursor) {
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
  @Operation(summary = "Get an ad in any status, with its moderation outcome")
  @ApiResponse(responseCode = "200", description = "The ad")
  @ApiResponse(
      responseCode = "404",
      description = "No ad with this id",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  AdResponse get(@PathVariable long id) {
    return AdResponse.from(adService.get(id));
  }
}
