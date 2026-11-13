package com.transformersas.marketplace.messaging.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Persistence boundary. IDs and context are resolved from existing marketplace records. */
public interface ConversationRepository {
    record Context(long sellerId,long storeId,String productName,String storeName) {}
    record Thread(long id,long buyerId,long sellerId,long storeId,long productId,Long orderId,
                  String productName,String storeName,boolean closed,boolean archived,long unread,long readThrough,
                  LocalDateTime updatedAt) {}
    record Message(long id,long conversationId,long senderId,String text,LocalDateTime createdAt,boolean image) {}
    record Image(String type,String hash,byte[] bytes) {}
    Optional<Context> context(long buyer,long product,Long order);
    long start(long buyer,long product,Long order,Context context);
    Optional<Thread> find(long account,long id,boolean lock);
    List<Thread> list(long account,boolean archived,int page);
    List<Message> messages(long thread,long after,long before,int size);
    Optional<Message> duplicate(long thread,long sender,String key);
    Message append(long thread,long sender,String key,String text,Image image);
    void read(long thread,long account,long through);
    void archive(long thread,long account,boolean archived);
    void close(long thread,boolean closed);
    boolean canSend(long account,long store,boolean seller);
    Optional<Message> message(long id);
    Optional<Image> image(long message);
}
