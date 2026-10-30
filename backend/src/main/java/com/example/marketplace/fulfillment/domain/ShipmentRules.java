package com.example.marketplace.fulfillment.domain;
public final class ShipmentRules {
 private ShipmentRules() { }
 public static boolean splitQuantities(java.util.List<Integer> current,java.util.List<Integer> moved) {
  if(current==null||moved==null||current.isEmpty()||current.size()!=moved.size()) { return false; }
  long total=0,selected=0;for(int i=0;i<current.size();i++) { if(current.get(i)==null||moved.get(i)==null||current.get(i)<1||moved.get(i)<0||moved.get(i)>current.get(i)) { return false; }total+=current.get(i);selected+=moved.get(i); }return selected>0&&selected<total;
 }
 public static boolean tracking(String number,String reason) { return number!=null&&number.matches("[A-Za-z0-9][A-Za-z0-9-]{0,63}")&&reason!=null&&!reason.isBlank()&&reason.codePointCount(0,reason.length())>=1&&reason.codePointCount(0,reason.length())<=200; }
 public static boolean allowed(String current,String target,String source) {
  return "SELLER".equals(source)&&"PREPARING".equals(current)&&"SHIPPED".equals(target)
   ||"MOCK_PROVIDER".equals(source)&&("SHIPPED".equals(current)&&"IN_TRANSIT".equals(target)||"IN_TRANSIT".equals(current)&&"DELIVERED".equals(target));
 }
 public static int rank(String state) { return java.util.List.of("PREPARING","SHIPPED","IN_TRANSIT","DELIVERED").indexOf(state); }
}
