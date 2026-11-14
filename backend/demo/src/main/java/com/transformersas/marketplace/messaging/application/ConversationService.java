package com.transformersas.marketplace.messaging.application;

import com.transformersas.marketplace.messaging.domain.ConversationRepository;
import com.transformersas.marketplace.messaging.domain.ConversationRepository.*;
import com.transformersas.marketplace.messaging.domain.ConversationRepository.Thread;
import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import com.transformersas.marketplace.reports.application.ContentVisibility;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.shared.files.ImageValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly=true)
public class ConversationService {
    public record Actor(long id,boolean seller) {}
    public record Input(String text,String clientKey) {}
    private final ConversationRepository repository;
    private final PublishNotificationUseCase notifications;
    private final ContentVisibility visibility;
    private final ImageValidator images;
    public ConversationService(ConversationRepository repository,PublishNotificationUseCase notifications,ContentVisibility visibility,
            @Value("${messaging.images.max-bytes:5242880}") long maxBytes) {
        this.repository=repository;this.notifications=notifications;this.visibility=visibility;
        if(maxBytes<1 || maxBytes>5242880) throw new IllegalArgumentException("messaging.images.max-bytes: 1..5242880");
        images=new ImageValidator(maxBytes,ImageValidator.DEFAULT_MAX_SIDE,ImageValidator.DEFAULT_MAX_PIXELS);
    }
    @Transactional public Thread start(Actor actor,long product,Long order) {
        if(actor.seller()) throw BusinessException.forbidden("CONVERSATION_BUYER","Solo un comprador puede iniciar una conversación.");
        var context=repository.context(actor.id(),product,order).orElseThrow(ConversationService::missing);
        if(context.sellerId()==actor.id()) throw BusinessException.invalid("CONVERSATION_SELF","No puedes contactar contigo mismo.");
        if(order==null && visibility.nonVisibleIds(ReportContentType.PUBLICACION).contains(Long.toString(product))) throw missing();
        return own(actor,repository.start(actor.id(),product,order,context),true);
    }
    private Thread own(Actor actor,long id,boolean lock) {
        var t=repository.find(actor.id(),id,lock).orElseThrow(ConversationService::missing);
        if((actor.seller()?t.sellerId():t.buyerId())!=actor.id()) throw missing();return t;
    }
    public Thread get(Actor actor,long id) {return own(actor,id,false);}
    public List<Thread> list(Actor actor,boolean archived,int page) {
        if(page<0 || page>10000) throw BusinessException.invalid("CONVERSATION_PAGE","Página inválida.");
        return repository.list(actor.id(),archived,page).stream().filter(t->(actor.seller()?t.sellerId():t.buyerId())==actor.id()).toList();
    }
    public List<Message> history(Actor actor,long id,long after,long before,int size) {
        own(actor,id,false);
        if(after<0 || before<1 || size<1 || size>100) throw BusinessException.invalid("MESSAGE_PAGE","Paginación inválida.");
        var hidden=visibility.nonVisibleIds(ReportContentType.MENSAJE);
        return repository.messages(id,after,before,size).stream().map(m->hidden.contains(Long.toString(m.id()))?render(m):m).toList();
    }
    private Message render(Message m) {
        String text=visibility.renderMessageText(Long.toString(m.id()),m.text());
        boolean visible=!visibility.nonVisibleIds(ReportContentType.MENSAJE).contains(Long.toString(m.id()));
        return new Message(m.id(),m.conversationId(),m.senderId(),text,m.createdAt(),visible && m.image());
    }
    @Transactional public Message send(Actor actor,long id,Input input,byte[] upload) {
        var t=own(actor,id,true); // Serializes sending, closing and read markers for stable cursor order.
        if(input==null || input.clientKey()==null || !input.clientKey().matches("[A-Za-z0-9_-]{16,64}"))
            throw BusinessException.invalid("MESSAGE_KEY","Se requiere una clave de envío de 16 a 64 caracteres.");
        String text=input.text()==null?"":input.text().strip();
        if(text.length()>4000) throw BusinessException.invalid("MESSAGE_TEXT","El mensaje admite hasta 4000 caracteres.");
        Image image=null;
        if(upload!=null) {var valid=images.validate(upload);image=new Image(valid.contentType(),valid.sha256(),upload);}
        var duplicate=repository.duplicate(id,actor.id(),input.clientKey());
        if(duplicate.isPresent()) {
            var old=duplicate.get();var oldImage=repository.image(old.id());
            if(!old.text().equals(text) || oldImage.isPresent()!=(image!=null) || (image!=null && !oldImage.orElseThrow().hash().equals(image.hash())))
                throw BusinessException.conflict("MESSAGE_KEY_REUSED","La clave ya corresponde a otro mensaje.");
            return render(old);
        }
        if(t.closed()) throw BusinessException.conflict("CONVERSATION_CLOSED","La conversación está cerrada. Reábrela para enviar.");
        if(!repository.canSend(actor.id(),t.storeId(),actor.seller()))
            throw BusinessException.forbidden("MESSAGE_RESTRICTED","Tu cuenta o tienda tiene una restricción. Puedes consultar el historial.");
        if(text.isEmpty() && image==null) throw BusinessException.invalid("MESSAGE_EMPTY","Escribe un mensaje o adjunta una imagen válida.");
        var message=repository.append(id,actor.id(),input.clientKey(),text,image);
        notifications.execute(new PublishNotificationCommand(actor.seller()?RecipientType.BUYER:RecipientType.STORE,
            actor.seller()?t.buyerId():t.storeId(),"CONVERSATION_MESSAGE","Nuevo mensaje", "Tienes un mensaje sobre "+t.productName(),
            "CONVERSATION",Long.toString(id),"conversation-message-"+message.id()));
        return render(message);
    }
    @Transactional public void read(Actor actor,long id,long through) {
        own(actor,id,true);
        if(through>0 && repository.message(through).filter(m->m.conversationId()==id).isEmpty()) throw missing();
        if(through<0) throw BusinessException.invalid("MESSAGE_READ","Marca de lectura inválida.");
        repository.read(id,actor.id(),through);
    }
    @Transactional public void archive(Actor actor,long id,boolean archived) {own(actor,id,true);repository.archive(id,actor.id(),archived);}
    @Transactional public void close(Actor actor,long id,boolean closed) {own(actor,id,true);repository.close(id,closed);}
    public Image image(Actor actor,long message) {
        var m=repository.message(message).orElseThrow(ConversationService::missing);own(actor,m.conversationId(),false);
        if(visibility.nonVisibleIds(ReportContentType.MENSAJE).contains(Long.toString(message))) throw missing();
        return repository.image(message).orElseThrow(ConversationService::missing);
    }
    private static BusinessException missing() {return BusinessException.notFound("CONVERSATION_NOT_FOUND","Conversación o contenido no disponible.");}
}
