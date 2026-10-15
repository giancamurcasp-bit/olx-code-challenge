package com.olx.ads.application;

/** The idempotency key was already used to create an ad with different content. */
public class IdempotencyKeyReusedException extends RuntimeException {

  public IdempotencyKeyReusedException() {
    super("Idempotency-Key was already used for a different ad");
  }
}
