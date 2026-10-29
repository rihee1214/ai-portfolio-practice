package com.example.marketplace.order.domain;

import com.example.marketplace.order.api.Orders;
import com.example.marketplace.promotion.api.Quotes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OrderRequestHashTest {
    @Test void canonicalInputsExcludeSecrets() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();var lines=List.of(new Quotes.LineRequest(a,1),new Quotes.LineRequest(b,2));
        var c=new Orders.Contact("구매자","01012345678","수령인","01012345678","01234","서울시","101호");
        var normalized=new Orders.Contact(" 구매자 ","010-1234-5678"," 수령인 ","+821012345678","01234"," 서울시 ","101호");
        String first=OrderRequestHash.calculate(lines,List.of(),"a".repeat(64),c,null,null);
        assertEquals(first,OrderRequestHash.calculate(lines.reversed(),List.of(),"a".repeat(64),normalized,null,null));
        assertTrue(first.matches("[0-9a-f]{64}"));assertFalse(first.contains("01012345678"));
        assertNotEquals(first,OrderRequestHash.calculate(lines,List.of(),"b".repeat(64),c,null,null));
        assertNotEquals(first,OrderRequestHash.calculate(List.of(new Quotes.LineRequest(a,2),new Quotes.LineRequest(b,2)),List.of(),"a".repeat(64),c,null,null));
        assertNotEquals(OrderRequestHash.calculate(lines,List.of(),"a".repeat(64),c,a,1),OrderRequestHash.calculate(lines,List.of(),"a".repeat(64),c,a,2));
        assertTrue(java.util.Arrays.stream(OrderRequestHash.class.getDeclaredMethods()).flatMap(m->java.util.Arrays.stream(m.getParameters())).noneMatch(p->p.getName().matches("password|proof|token|recovery")));
    }
}
