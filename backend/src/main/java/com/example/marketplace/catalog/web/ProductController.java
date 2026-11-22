package com.example.marketplace.catalog.web;

import com.example.marketplace.catalog.api.Products;
import com.example.marketplace.catalog.api.ProductDescriptionMedia.Upload;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@RestController @Profile("db")
public class ProductController {
    private final Products products;
    public ProductController(Products products) { this.products=products; }
    @GetMapping("/api/products") public ResponseEntity<Products.Page<Products.PublicProduct>> published(@RequestParam(defaultValue="0") int page) { return response(200,products.published(page)); }
    @GetMapping("/api/products/{slug}") public ResponseEntity<Products.PublicProduct> published(@PathVariable String slug) { return response(200,products.published(slug)); }
    @GetMapping("/api/seller/products") public ResponseEntity<Products.Page<Products.Product>> list(Principal p,@RequestParam(defaultValue="0") int page) { return response(200,products.list(UUID.fromString(p.getName()),page)); }
    @GetMapping("/api/seller/products/{id}") public ResponseEntity<Products.Product> own(Principal p,@PathVariable UUID id) { return response(200,products.own(UUID.fromString(p.getName()),id)); }
    @PostMapping(path="/api/seller/products",consumes="application/json")
    public ResponseEntity<Products.Product> create(Principal p,HttpSession session,HttpServletRequest request,@RequestBody JsonNode body) {
        return save(p,session,request,null,ProductInput.read(body,Map.of()));
    }
    @PutMapping(path="/api/seller/products/{id}",consumes="application/json")
    public ResponseEntity<Products.Product> update(Principal p,HttpSession session,HttpServletRequest request,@PathVariable UUID id,@RequestBody JsonNode body) {
        return save(p,session,request,id,ProductInput.read(body,Map.of()));
    }
    @PostMapping(path="/api/seller/products",consumes="multipart/form-data")
    public ResponseEntity<Products.Product> createWithImages(Principal p,HttpSession session,HttpServletRequest request,
            @RequestPart("product") JsonNode body,@RequestPart("images") List<MultipartFile> images) throws IOException {
        return save(p,session,request,null,ProductInput.read(body,uploads(images)));
    }
    @PutMapping(path="/api/seller/products/{id}",consumes="multipart/form-data")
    public ResponseEntity<Products.Product> updateWithImages(Principal p,HttpSession session,HttpServletRequest request,
            @PathVariable UUID id,@RequestPart("product") JsonNode body,@RequestPart("images") List<MultipartFile> images) throws IOException {
        return save(p,session,request,id,ProductInput.read(body,uploads(images)));
    }
    private ResponseEntity<Products.Product> save(Principal p,HttpSession session,HttpServletRequest request,UUID id,Products.Input input) {
        return response(id==null?201:200,products.saveForSession(UUID.fromString(p.getName()),session.getId(),
                (Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),id,input));
    }
    private static Map<Integer, Upload> uploads(List<MultipartFile> images) throws IOException {
        if(images.isEmpty() || images.size()>com.example.marketplace.catalog.api.ProductDescription.MAX_IMAGES
                || images.stream().mapToLong(MultipartFile::getSize).sum()>com.example.marketplace.catalog.api.ProductDescription.MAX_IMAGE_BYTES) {
            throw new com.example.marketplace.catalog.api.CatalogException(400);
        }
        var result=new HashMap<Integer, Upload>();
        for(int i=0;i<images.size();i++) {
            var image=images.get(i);
            result.put(i,new Upload(image.getOriginalFilename(),image.getBytes()));
        }
        return result;
    }
    private <T> ResponseEntity<T> response(int status,T value) { return ResponseEntity.status(status).header("Cache-Control","no-store").body(value); }
}
