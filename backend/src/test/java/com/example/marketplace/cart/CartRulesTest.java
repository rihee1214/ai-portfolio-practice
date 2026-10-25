package com.example.marketplace.cart;
import com.example.marketplace.cart.api.Carts;
import com.example.marketplace.cart.domain.CartRules;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
class CartRulesTest {
    @Test void quantitiesAndCapacityRejectInvalidInputs() {
        UUID id=UUID.randomUUID();
        for(int q:List.of(-1,0,100,Integer.MAX_VALUE)) { assertThrows(IllegalArgumentException.class,()->CartRules.normalize(List.of(new Carts.Input(id,q)))); }
        assertThrows(IllegalArgumentException.class,()->CartRules.normalize(null));
        assertThrows(IllegalArgumentException.class,()->CartRules.normalize(List.of(new Carts.Input(null,1))));
        assertThrows(IllegalArgumentException.class,()->CartRules.normalize(java.util.stream.IntStream.range(0,101).mapToObj(i->new Carts.Input(UUID.randomUUID(),1)).toList()));
        assertEquals(99,CartRules.normalize(List.of(new Carts.Input(id,99))).getFirst().quantity());
    }
    @Test void duplicateSkuQuantitiesSaturateAndSortDeterministically() {
        UUID first=UUID.fromString("00000000-0000-4000-8000-000000000001"),second=UUID.fromString("00000000-0000-4000-8000-000000000002");
        assertEquals(List.of(new Carts.Input(first,99),new Carts.Input(second,2)),CartRules.normalize(List.of(new Carts.Input(second,2),new Carts.Input(first,70),new Carts.Input(first,60))));
    }
}
