package com.example.marketplace.guest;
import com.example.marketplace.guest.api.GuestPassword;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
class GuestPasswordTest {
    @Test void preservesSpacesAndUnicodeWithinTwelveTo128CodePoints() {
        String value="  example pass  "; assertEquals(value,new GuestPassword(value,value).value());
        for(String valid:new String[]{"x".repeat(12),"x".repeat(128),"😀".repeat(128)," ".repeat(12)}) {
            assertEquals(valid,new GuestPassword(valid,valid).value());
        }
    }
    @Test void rejectsMismatchBoundsAndMalformedUnicode() {
        for(String invalid:new String[]{"", "x".repeat(11),"x".repeat(129),"😀".repeat(129),"\uD800".repeat(12)}) {
            assertThrows(IllegalArgumentException.class,()->new GuestPassword(invalid,invalid));
        }
        assertThrows(IllegalArgumentException.class,()->new GuestPassword("long password","long password "));
        assertThrows(IllegalArgumentException.class,()->new GuestPassword(null,null));
    }
}
