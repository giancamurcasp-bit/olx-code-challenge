package com.olx.ads.adapter.in.web.error;

/** A query parameter the API can't use; reported like a field validation error. */
public class InvalidParameterException extends RuntimeException {

  private final String parameter;

  public InvalidParameterException(String parameter, String message) {
    super(message);
    this.parameter = parameter;
  }

  String getParameter() {
    return parameter;
  }
}
