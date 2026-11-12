package com.transformersas.marketplace.messaging.infrastructure;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.messaging.application.ConversationService;
import com.transformersas.marketplace.messaging.application.ConversationService.*;
import com.transformersas.marketplace.messaging.domain.ConversationRepository.Message;
import com.transformersas.marketplace.messaging.domain.ConversationRepository.Thread;
import com.transformersas.marketplace.users.domain.model.Role;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    public record Start(long productId,Long orderId) {}
    public record State(boolean value) {}
    public record Read(long through) {}
    private final ConversationService service;
    public ConversationController(ConversationService service) {this.service=service;}
    private Actor actor(AccountPrincipal p) {
        if(p==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(p.activeRole()!=Role.COMPRADOR && p.activeRole()!=Role.VENDEDOR) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return new Actor(p.accountId(),p.activeRole()==Role.VENDEDOR);
    }
    @PostMapping public Thread start(@AuthenticationPrincipal AccountPrincipal p,@RequestBody Start input) {return service.start(actor(p),input.productId(),input.orderId());}
    @GetMapping public List<Thread> list(@AuthenticationPrincipal AccountPrincipal p,@RequestParam(defaultValue="false") boolean archived,@RequestParam(defaultValue="0") int page) {return service.list(actor(p),archived,page);}
    @GetMapping("/{id}") public Thread get(@AuthenticationPrincipal AccountPrincipal p,@PathVariable long id) {return service.get(actor(p),id);}
    @GetMapping("/{id}/messages") public List<Message> history(@AuthenticationPrincipal AccountPrincipal p,@PathVariable long id,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="9223372036854775807") long before,@RequestParam(defaultValue="50") int size) {return service.history(actor(p),id,after,before,size);}
    @PostMapping(value="/{id}/messages",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Message send(@AuthenticationPrincipal AccountPrincipal p,@PathVariable long id,@RequestBody Input input) {return service.send(actor(p),id,input,null);}
    @PostMapping(value="/{id}/messages",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Message upload(@AuthenticationPrincipal AccountPrincipal p,@PathVariable long id,@RequestParam(defaultValue="") String text,@RequestParam String clientKey,@RequestPart(required=false) MultipartFile image) throws IOException {
        var actor=actor(p);return service.send(actor,id,new Input(text,clientKey),image==null?null:image.getBytes());
    }
    @PostMapping("/{id}/read") public void read(@AuthenticationPrincipal AccountPrincipal p,@PathVariable long id,@RequestBody Read input) {service.read(actor(p),id,input.through());}
    @PostMapping("/{id}/archive") public void archive(@AuthenticationPrincipal AccountPrincipal p,@PathVariable long id,@RequestBody State input) {service.archive(actor(p),id,input.value());}
    @PostMapping("/{id}/closed") public void close(@AuthenticationPrincipal AccountPrincipal p,@PathVariable long id,@RequestBody State input) {service.close(actor(p),id,input.value());}
    @GetMapping("/images/{id}") public ResponseEntity<byte[]> image(@AuthenticationPrincipal AccountPrincipal p,@PathVariable long id) {
        var image=service.image(actor(p),id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.type())).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options","nosniff").header("Content-Disposition","inline").body(image.bytes());
    }
}
