package com.transformersas.marketplace.notifications.application.usecase;

import com.transformersas.marketplace.notifications.domain.repository.NotificationRepository;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ListOwnNotifications {
    public record Item(Long id, String title, String message, LocalDateTime createdAt, Long orderId, Long productId, Long conversationId) { }
    public record Page(List<Item> items, boolean hasMore) { }
    private final NotificationRepository repository;
    public ListOwnNotifications(NotificationRepository repository) { this.repository=repository; }
    @Transactional(readOnly=true)
    public Page execute(RecipientType type, Long recipient, int page, int size) {
        if (page<0 || page>100000 || size<1 || size>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Paginación inválida: page entre 0 y 100000; size entre 1 y 50");
        var rows=repository.findByRecipient(type,recipient,page*size,size+1);
        return new Page(rows.stream().limit(size).map(n -> new Item(n.id(),n.title(),n.message(),n.createdAt(),
            "ORDER".equals(n.referenceType()) && n.referenceId().matches("[1-9][0-9]{0,17}")
                ? Long.valueOf(n.referenceId()) : null,
            "PRODUCT".equals(n.referenceType()) && n.referenceId().matches("[1-9][0-9]{0,17}")
                ? Long.valueOf(n.referenceId()) : null,
            "CONVERSATION".equals(n.referenceType()) && n.referenceId().matches("[1-9][0-9]{0,17}")
                ? Long.valueOf(n.referenceId()) : null)).toList(),rows.size()>size);
    }
}
