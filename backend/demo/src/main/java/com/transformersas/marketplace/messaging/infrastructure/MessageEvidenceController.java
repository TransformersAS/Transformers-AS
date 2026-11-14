package com.transformersas.marketplace.messaging.infrastructure;

import com.transformersas.marketplace.messaging.domain.ConversationRepository;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Support can read only an attachment referenced by a CU20 report, even after moderation hides it. */
@RestController
@RequestMapping("/api/support/messaging/images")
public class MessageEvidenceController {
    private final ConversationRepository repository;
    private final JdbcTemplate jdbc;
    public MessageEvidenceController(ConversationRepository repository,JdbcTemplate jdbc) {this.repository=repository;this.jdbc=jdbc;}
    @GetMapping("/{id}") public ResponseEntity<byte[]> image(@PathVariable long id) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM reports WHERE content_type='MENSAJE' AND content_id=?",Integer.class,Long.toString(id))==0)
            throw BusinessException.notFound("MESSAGE_EVIDENCE_NOT_FOUND","Evidencia no disponible.");
        var image=repository.image(id).orElseThrow(()->BusinessException.notFound("MESSAGE_EVIDENCE_NOT_FOUND","Evidencia no disponible."));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.type())).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options","nosniff").body(image.bytes());
    }
}
