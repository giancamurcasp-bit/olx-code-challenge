package com.olx.ads.domain;

/** Thrown when an ad would violate one of its invariants. */
public class InvalidAdException extends RuntimeException {

  private final String field;

  public InvalidAdException(String field, String message) {
    super(message);
    this.field = field;
  }

  /** The input field that broke the rule, so adapters can point the client to it. */
  public String getField() {
    return field;
  }
}
