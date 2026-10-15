package com.olx.ads.domain;

/** Thrown when an ad would violate one of its invariants. */
public class InvalidAdException extends RuntimeException {

  public InvalidAdException(String message) {
    super(message);
  }
}
