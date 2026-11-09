package com.transformersas.marketplace.notifications.infrastructure.queue;

import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/admin/notifications/jobs")
public class NotificationRetryController {
    private final JdbcTemplate jdbc;
    public NotificationRetryController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostMapping("/{id}/retry") public void retry(@PathVariable Long id) {
        int updated=jdbc.update("UPDATE notification_jobs SET status='PENDING',attempts=0,locked_until=NULL,last_error=NULL,available_at=CURRENT_TIMESTAMP(6) WHERE notification_id=? AND status='FAILED'",id);
        if(updated==0) throw new ResponseStatusException(CONFLICT,"No hay un envío fallido para reintentar");
    }
}
