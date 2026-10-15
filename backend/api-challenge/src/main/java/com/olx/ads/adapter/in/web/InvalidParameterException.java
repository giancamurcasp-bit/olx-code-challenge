package com.olx.ads.adapter.in.web;

/** A query parameter the API can't use; reported like a field validation error. */
class InvalidParameterException extends RuntimeException {

  private final String parameter;

  InvalidParameterException(String parameter, String message) {
    super(message);
    this.parameter = parameter;
  }

  String getParameter() {
    return parameter;
  }
}
