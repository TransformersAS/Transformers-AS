package com.transformersas.marketplace.orders.infrastructure.web.controller;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.orders.application.usecase.GenerateBuyerOrderReceiptUseCase;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderReceiptController {
    private final GenerateBuyerOrderReceiptUseCase receipts;
    public OrderReceiptController(GenerateBuyerOrderReceiptUseCase receipts) { this.receipts = receipts; }

    @GetMapping(value = "/{id}/receipt", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> receipt(@PathVariable Long id, @AuthenticationPrincipal AccountPrincipal principal,
                                           @RequestParam(defaultValue = "false") boolean download, HttpServletRequest request) {
        byte[] pdf = receipts.execute(id, principal);
        var disposition = download ? ContentDisposition.attachment() : ContentDisposition.inline();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename("comprobante-pedido-" + id + ".pdf").build().toString())
                .cacheControl(CacheControl.noStore()).contentLength(pdf.length)
                .body("HEAD".equals(request.getMethod()) ? null : pdf);
    }
}
