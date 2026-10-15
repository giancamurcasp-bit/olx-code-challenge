package com.olx.ads.application;

public class AdNotFoundException extends RuntimeException {

  public AdNotFoundException(long id) {
    super("ad " + id + " not found");
  }
}
