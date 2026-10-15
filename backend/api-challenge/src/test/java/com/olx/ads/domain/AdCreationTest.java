package com.olx.ads.domain;

import static com.olx.ads.domain.AdFixtures.CATEGORY;
import static com.olx.ads.domain.AdFixtures.DESCRIPTION;
import static com.olx.ads.domain.AdFixtures.IMAGES;
import static com.olx.ads.domain.AdFixtures.NOW;
import static com.olx.ads.domain.AdFixtures.PRICE;
import static com.olx.ads.domain.AdFixtures.TITLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdCreationTest {

  @Test
  void newAdStartsPendingAndIsDueForModerationImmediately() {
    Ad ad = AdFixtures.pendingAd();

    assertThat(ad.getStatus()).isEqualTo(AdStatus.PENDING);
    assertThat(ad.getModerationAttempts()).isZero();
    assertThat(ad.getNextAttemptAt()).isEqualTo(NOW);
    assertThat(ad.getCreatedAt()).isEqualTo(NOW);
    assertThat(ad.getRejectionReason()).isNull();
    assertThat(ad.getLastModerationError()).isNull();
  }

  // --- price ---

  @ParameterizedTest
  @ValueSource(strings = {"0", "0.00", "-0.01", "-100"})
  void rejectsNonPositivePrice(String price) {
    assertInvalid(() -> createWithPrice(new BigDecimal(price)));
  }

  @Test
  void rejectsPriceWithMoreThanTwoDecimalPlaces() {
    assertInvalid(() -> createWithPrice(new BigDecimal("10.001")));
  }

  @Test
  void rejectsPriceThatDoesNotFitTheDatabaseColumn() {
    assertInvalid(() -> createWithPrice(new BigDecimal("10000000000.00")));
  }

  @Test
  void acceptsTheLargestStorablePrice() {
    assertThatCode(() -> createWithPrice(new BigDecimal("9999999999.99")))
        .doesNotThrowAnyException();
  }

  // --- title ---

  @ParameterizedTest
  @ValueSource(ints = {9, 101})
  void rejectsTitleOutside10To100Characters(int length) {
    assertInvalid(() -> createWithTitle("a".repeat(length)));
  }

  @ParameterizedTest
  @ValueSource(ints = {10, 100})
  void acceptsTitleAtTheLengthBoundaries(int length) {
    assertThatCode(() -> createWithTitle("a".repeat(length))).doesNotThrowAnyException();
  }

  @Test
  void countsTitleLengthInCharactersNotUtf16Units() {
    String emoji = "😀"; // 1 character, 2 UTF-16 units

    assertThatCode(() -> createWithTitle(emoji.repeat(10))).doesNotThrowAnyException();
    assertThatCode(() -> createWithTitle(emoji.repeat(100))).doesNotThrowAnyException();
    assertInvalid(() -> createWithTitle(emoji.repeat(101)));
  }

  @Test
  void ignoresSurroundingWhitespaceWhenCountingTitleLength() {
    assertInvalid(() -> createWithTitle("   " + "a".repeat(9) + "   "));
  }

  @Test
  void storesTitleTrimmed() {
    Ad ad = createWithTitle("  " + TITLE + "  ");

    assertThat(ad.getTitle()).isEqualTo(TITLE);
  }

  // --- description ---

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void rejectsBlankDescription(String description) {
    assertInvalid(() -> Ad.create(TITLE, description, PRICE, CATEGORY, IMAGES, NOW));
  }

  // --- images ---

  @Test
  void rejectsAdWithoutImages() {
    assertInvalid(() -> createWithImages(List.of()));
  }

  @Test
  void acceptsUpTo10Images() {
    assertThatCode(() -> createWithImages(images(10))).doesNotThrowAnyException();
  }

  @Test
  void rejectsMoreThan10Images() {
    assertInvalid(() -> createWithImages(images(11)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "   ", "not a url", "/images/a.jpg", "ftp://example.com/a.jpg", "http://"})
  void rejectsImagesThatAreNotAbsoluteHttpUrls(String image) {
    assertInvalid(() -> createWithImages(List.of(image)));
  }

  // --- required fields ---

  @Test
  void rejectsMissingRequiredFields() {
    assertInvalid(() -> Ad.create(null, DESCRIPTION, PRICE, CATEGORY, IMAGES, NOW));
    assertInvalid(() -> Ad.create(TITLE, null, PRICE, CATEGORY, IMAGES, NOW));
    assertInvalid(() -> Ad.create(TITLE, DESCRIPTION, null, CATEGORY, IMAGES, NOW));
    assertInvalid(() -> Ad.create(TITLE, DESCRIPTION, PRICE, null, IMAGES, NOW));
    assertInvalid(() -> Ad.create(TITLE, DESCRIPTION, PRICE, CATEGORY, null, NOW));
    assertInvalid(
        () -> Ad.create(TITLE, DESCRIPTION, PRICE, CATEGORY, Collections.singletonList(null), NOW));
  }

  // --- helpers ---

  private static void assertInvalid(Runnable creation) {
    assertThatThrownBy(creation::run).isInstanceOf(InvalidAdException.class);
  }

  private static Ad createWithTitle(String title) {
    return Ad.create(title, DESCRIPTION, PRICE, CATEGORY, IMAGES, NOW);
  }

  private static Ad createWithPrice(BigDecimal price) {
    return Ad.create(TITLE, DESCRIPTION, price, CATEGORY, IMAGES, NOW);
  }

  private static Ad createWithImages(List<String> images) {
    return Ad.create(TITLE, DESCRIPTION, PRICE, CATEGORY, images, NOW);
  }

  private static List<String> images(int count) {
    return java.util.stream.IntStream.rangeClosed(1, count)
        .mapToObj(i -> "https://example.com/" + i + ".jpg")
        .toList();
  }
}
