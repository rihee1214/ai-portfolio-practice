package com.example.marketplace.jdbcfixture;

/** Child-JVM endpoint selection proof; opens no connection and receives no credentials. */
public final class DedicatedEndpointPropagationProbe {
 private DedicatedEndpointPropagationProbe() { }
 public static void main(String[] args) {
  DedicatedDatabaseTarget.validate(System.getenv("D2_DB_URL"),System.getenv("D2_DB_USERNAME"),System.getenv("D2_DB_NAME"));
  System.out.println("TARGET_ALLOWED");
 }
}
