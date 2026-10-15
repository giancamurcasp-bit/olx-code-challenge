package com.olx.ads.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Ad categories accepted by the moderation provider (DECISIONS.md, section 5). */
public enum Category {
  ELETRONICOS,
  MOVEIS,
  VEICULOS,
  IMOVEIS,
  ESPORTES,
  COLECIONAVEIS;

  /** The identifier used in the API, the database and the moderation provider. */
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Category fromValue(String value) {
    return Arrays.stream(values())
        .filter(category -> category.value().equals(value))
        .findFirst()
        .orElseThrow(
            () ->
                new InvalidAdException(
                    "category",
                    "must be one of: "
                        + Arrays.stream(values())
                            .map(Category::value)
                            .collect(Collectors.joining(", "))));
  }
}
