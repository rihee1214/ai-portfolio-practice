package com.example.marketplace.catalog.api;

import java.util.List;
import java.util.UUID;

public interface DashboardProducts {
    record Product(UUID id,UUID store,String state,boolean hidden,boolean published) { }
    record Sku(UUID id,UUID store) { }
    List<Product> products();
    List<Sku> skus();
}
