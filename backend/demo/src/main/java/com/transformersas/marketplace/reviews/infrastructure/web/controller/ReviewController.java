package com.transformersas.marketplace.reviews.infrastructure.web.controller;
import com.transformersas.marketplace.reviews.application.*;
import com.transformersas.marketplace.auth.infrastructure.security.*;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.io.IOException;

@RestController
public class ReviewController {
    private final ReviewService service;private final CurrentActorProvider seller;
    public ReviewController(ReviewService service,CurrentActorProvider seller) {this.service=service;this.seller=seller;}
    @GetMapping("/api/reviews/products/{product}")
    public List<ReviewService.View> product(@PathVariable long product,@RequestParam(defaultValue="0") int page) {return service.product(product,page);}
    @GetMapping("/api/reviews/products/{product}/summary")
    public ReviewService.Summary summary(@PathVariable long product) {service.product(product,0);return service.summary(product);}
    @GetMapping("/api/reviews/mine")
    public List<ReviewService.View> mine(@AuthenticationPrincipal AccountPrincipal p,@RequestParam(defaultValue="0") int page) {return service.mine(BuyerAccess.accountId(p),page);}
    @GetMapping("/api/reviews/eligibility")
    public ReviewService.Eligibility eligibility(@AuthenticationPrincipal AccountPrincipal p,@RequestParam Long orderId,@RequestParam Long productId) {return service.eligibility(BuyerAccess.accountId(p),orderId,productId);}
    @PostMapping(value="/api/reviews",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ReviewService.View create(@AuthenticationPrincipal AccountPrincipal p,@RequestBody ReviewService.Input input) {return service.create(BuyerAccess.accountId(p),input,List.of());}
    @PostMapping(value="/api/reviews",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReviewService.View upload(@AuthenticationPrincipal AccountPrincipal p,@RequestPart("review") ReviewService.Input input,@RequestPart(required=false) List<MultipartFile> images) {return service.create(BuyerAccess.accountId(p),input,bytes(images));}
    @PutMapping(value="/api/reviews/{id}",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ReviewService.View edit(@AuthenticationPrincipal AccountPrincipal p,@PathVariable long id,@RequestBody ReviewService.Input input) {return service.edit(BuyerAccess.accountId(p),id,input,List.of());}
    @PutMapping(value="/api/reviews/{id}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReviewService.View editUpload(@AuthenticationPrincipal AccountPrincipal p,@PathVariable long id,@RequestPart("review") ReviewService.Input input,@RequestPart(required=false) List<MultipartFile> images) {return service.edit(BuyerAccess.accountId(p),id,input,bytes(images));}
    @DeleteMapping("/api/reviews/{id}") public void delete(@AuthenticationPrincipal AccountPrincipal p,@PathVariable long id) {service.delete(BuyerAccess.accountId(p),id);}
    private List<byte[]> bytes(List<MultipartFile> files) {
        if(files==null) return List.of();
        if(files.size()>3) throw BusinessException.invalid("REVIEW_IMAGES","Adjunta como máximo tres imágenes.");
        return files.stream().map(f->{
            if(f.getSize()>5L*1024*1024) throw BusinessException.invalid("IMAGE_TOO_LARGE","Cada imagen admite hasta 5 MB.");
            try{return f.getBytes();} catch(IOException e){throw BusinessException.invalid("IMAGE_CORRUPT","No se pudo leer la imagen.");}
        }).toList();
    }
    @GetMapping("/api/reviews/images/{id}")
    public ResponseEntity<byte[]> image(@PathVariable long id,WebRequest request) {
        var image=service.image(id);String etag='"'+image.hash()+'"';
        if(request.checkNotModified(etag)) return ResponseEntity.status(304).eTag(etag).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.type())).eTag(etag)
                .header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noCache()).body(image.bytes());
    }
    @GetMapping("/api/seller/reviews")
    public List<ReviewService.View> seller(@RequestParam(required=false) Long productId,@RequestParam(required=false) Integer rating,
            @RequestParam(required=false) Boolean answered,@RequestParam(defaultValue="0") int page) {return service.seller(seller.storeId(),seller.actorId(),productId,rating,answered,page);}
    @GetMapping("/api/seller/reviews/summary")
    public List<ReviewService.Summary> sellerSummary() {return List.copyOf(service.summaries(null,seller.storeId()).values());}
    @GetMapping("/api/seller/reviews/{id}/purchase")
    public ReviewService.Purchase purchase(@PathVariable long id) {return service.sellerPurchase(seller.storeId(),id);}
    public record Response(String text) { }
    @PutMapping("/api/seller/reviews/{id}/reply")
    public void reply(@PathVariable long id,@RequestBody Response response) {service.reply(seller.storeId(),seller.actorId(),id,response==null?null:response.text(),false);}
    @DeleteMapping("/api/seller/reviews/{id}/reply")
    public void removeReply(@PathVariable long id) {service.reply(seller.storeId(),seller.actorId(),id,null,true);}
}
