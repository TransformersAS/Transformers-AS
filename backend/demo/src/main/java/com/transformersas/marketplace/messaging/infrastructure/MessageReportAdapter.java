package com.transformersas.marketplace.messaging.infrastructure;

import com.transformersas.marketplace.messaging.domain.ConversationRepository;
import com.transformersas.marketplace.reports.domain.port.*;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** CU-20 owns intake/moderation. Immutable source remains available as evidence. */
@Component
@Transactional(readOnly=true)
public class MessageReportAdapter implements ReportableContentVerifier,ContentOwnerResolver,ContentSnapshotProvider {
    private final ConversationRepository repository;
    public MessageReportAdapter(ConversationRepository repository) {this.repository=repository;}
    public ReportContentType type() {return ReportContentType.MENSAJE;}
    private long id(String value) {try {return Long.parseLong(value);} catch(NumberFormatException e) {return -1;}}
    public boolean isAccessibleTo(String content,String reporter) {
        return repository.message(id(content)).filter(m->repository.find(id(reporter),m.conversationId(),false).isPresent()).isPresent();
    }
    public Optional<String> ownerAccountId(String content) {return repository.message(id(content)).map(m->Long.toString(m.senderId()));}
    public Optional<ContentSnapshot> load(String content) {
        return repository.message(id(content)).map(m->{
            Map<String,String> attributes=new LinkedHashMap<>();
            attributes.put("conversationId",Long.toString(m.conversationId()));attributes.put("createdAt",m.createdAt().toString());
            repository.image(m.id()).ifPresent(i->{attributes.put("imageUrl","/api/support/messaging/images/"+m.id());attributes.put("imageSha256",i.hash());attributes.put("imageContentType",i.type());});
            return new ContentSnapshot("Mensaje #"+m.id(),m.text(),Long.toString(m.senderId()),attributes);
        });
    }
}
