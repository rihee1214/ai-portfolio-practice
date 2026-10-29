package com.example.marketplace.fulfillment.domain;
public final class ShipmentRules {
 private ShipmentRules() { }
 public static boolean allowed(String current,String target,String source) {
  return "SELLER".equals(source)&&"PREPARING".equals(current)&&"SHIPPED".equals(target)
   ||"MOCK_PROVIDER".equals(source)&&("SHIPPED".equals(current)&&"IN_TRANSIT".equals(target)||"IN_TRANSIT".equals(current)&&"DELIVERED".equals(target));
 }
 public static int rank(String state) { return java.util.List.of("PREPARING","SHIPPED","IN_TRANSIT","DELIVERED").indexOf(state); }
}
