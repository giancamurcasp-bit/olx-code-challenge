package com.olx.ads;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AdsApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(AdsApiApplication.class, args);
  }
}
