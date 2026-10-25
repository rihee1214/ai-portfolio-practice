package com.example.marketplace.catalog.web;
import com.example.marketplace.catalog.api.Browse;
import com.example.marketplace.catalog.api.Products;
import com.example.marketplace.catalog.api.CatalogException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;

@RestController @Profile("db")
public class BrowseController {
    private final Browse browse;
    public BrowseController(Browse browse) { this.browse=browse; }
    @GetMapping("/api/browse/products")
    public ResponseEntity<Products.Page<Browse.Item>> search(@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID category,
        @RequestParam(required=false) String store,@RequestParam(required=false) Long minPrice,@RequestParam(required=false) Long maxPrice,
        @RequestParam(defaultValue="false") String inStock,@RequestParam(defaultValue="newest") String sort,@RequestParam(defaultValue="0") int page) {
        if(!inStock.equals("true")&&!inStock.equals("false")) { throw new CatalogException(400); }
        return response(browse.search(new Browse.Query(q,category,store,minPrice,maxPrice,Boolean.parseBoolean(inStock),sort,page)));
    }
    @GetMapping("/api/browse/products/{slug}") public ResponseEntity<Browse.Detail> detail(@PathVariable String slug) { return response(browse.detail(slug)); }
    private <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().header("Cache-Control","no-store").body(value); }
}
