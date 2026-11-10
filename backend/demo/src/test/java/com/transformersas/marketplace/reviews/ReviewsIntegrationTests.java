package com.transformersas.marketplace.reviews;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.reviews.application.*;
import com.transformersas.marketplace.reviews.infrastructure.ReviewContentConfiguration;
import com.transformersas.marketplace.reports.domain.model.*;
import com.transformersas.marketplace.reports.domain.port.*;
import com.transformersas.marketplace.reports.application.ContentModerationStateService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import java.util.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReviewsIntegrationTests extends AbstractIntegrationTest {
 @Autowired ReviewService service;@Autowired ContentModerationStateService moderation;
 @Autowired List<ContentSnapshotProvider> snapshots;
 @Autowired List<ContentOwnerResolver> owners;
 @Autowired List<ReportableContentVerifier> verifiers;
 Session buyer,other,seller,outsider;long account,product,order;
 @BeforeEach void data() throws Exception {
  for(String table:List.of("report_evidence_files","moderation_notifications","moderation_referrals","content_moderation_state","information_requests","moderation_actions","report_evidences","reports","moderation_cases","audit_logs"))jdbc.update("DELETE FROM "+table);
  seller=sellerOfStore("review-seller@test.example",1);seedStore(2,"Otra tienda");outsider=sellerOfStore("other-seller@test.example",2);
  buyer=sessionWithRole("review-buyer@test.example","COMPRADOR");other=sessionWithRole("review-other@test.example","COMPRADOR");
  account=jdbc.queryForObject("SELECT id FROM user_accounts WHERE email='review-buyer@test.example'",Long.class);
  product=seedProduct(1,"Producto comprado",20,"100");order=seedOrder(1,"DELIVERED",product,1,"100");jdbc.update("UPDATE orders SET account_id=? WHERE id=?",account,order);
 }
 ReviewService.Input input(Integer stars,String text){return new ReviewService.Input(order,product,stars,text,null);}
 long create() {return service.create(account,input(5,"Excelente"),List.of()).id();}
 byte[] png() throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(10,10,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
 org.springframework.test.web.servlet.ResultActions upload(Session session,org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder request)throws Exception {return mvc.perform(request.cookie(session.cookie()).header(session.csrfHeader(),session.csrfToken()));}
 MockMultipartFile body(ReviewService.Input input)throws Exception{return new MockMultipartFile("review","review.json","application/json",json.writeValueAsBytes(input));}
 void hidden(long id,ReportContentType type)throws Exception {
  var result=perform(other,post("/api/reports").contentType("application/json").content(json.writeValueAsString(Map.of("contentType",type.name(),"contentId",Long.toString(id),"reason","SPAM","description","Contenido inapropiado")))).andExpect(status().isCreated()).andReturn();
  long report=json.readTree(result.getResponse().getContentAsString()).get("id").asLong();long c=jdbc.queryForObject("SELECT case_id FROM reports WHERE id=?",Long.class,report);
  jdbc.update("INSERT INTO content_moderation_state(content_type,content_id,state,case_id,updated_at) VALUES (?,?,'OCULTO_TEMPORAL',?,CURRENT_TIMESTAMP(6))",type.name(),Long.toString(id),c);
 }
 @Test void httpLifecycleSupportsRatingOnlyAndUpdatesCatalogAverage() throws Exception {
  perform(buyer,get("/api/reviews/eligibility").param("orderId",""+order).param("productId",""+product)).andExpect(jsonPath("$.eligible").value(true));
  var result=perform(buyer,post("/api/reviews").contentType("application/json").content(json.writeValueAsString(input(5,null)))).andExpect(status().isOk()).andReturn();long id=json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
  mvc.perform(get("/api/reviews/products/"+product)).andExpect(status().isOk()).andExpect(jsonPath("$[0].orderId").isEmpty()).andExpect(jsonPath("$[0].accountId").doesNotExist());
  perform(buyer,get("/api/reviews/mine")).andExpect(jsonPath("$[0].orderId").value(order));
  perform(buyer,put("/api/reviews/"+id).contentType("application/json").content(json.writeValueAsString(input(2,"Corregida")))).andExpect(jsonPath("$.rating").value(2));
  mvc.perform(get("/api/products/"+product)).andExpect(jsonPath("$.ratingAverage").value(2.0)).andExpect(jsonPath("$.reviewCount").value(1));
  mvc.perform(get("/api/reviews/products/"+product+"/summary")).andExpect(jsonPath("$.distribution[1]").value(1));
  perform(buyer,delete("/api/reviews/"+id)).andExpect(status().isOk());assertThat(service.summary(product).count()).isZero();assertThat(service.product(product,0)).isEmpty();
 }
 @Test void otherPurchaseAndUndeliveredOrdersCannotBeReviewed() throws Exception {
  perform(other,post("/api/reviews").contentType("application/json").content(json.writeValueAsString(input(5,"x")))).andExpect(status().isNotFound());
  for(String state:List.of("CONFIRMED","IN_PREPARATION","READY_FOR_DISPATCH","IN_TRANSIT","CANCELLED","RETURNED_TO_SELLER")){
   jdbc.update("UPDATE orders SET status=? WHERE id=?",state,order);
   assertThat(service.eligibility(account,order,product).eligible()).isFalse();
   perform(buyer,post("/api/reviews").contentType("application/json").content(json.writeValueAsString(input(4,"x")))).andExpect(status().isConflict());
  }
  assertThat(count("product_reviews")).isZero();
 }
 @Test void authRolesCsrfAndCrossAccountMutationsAreEnforced() throws Exception {
  mvc.perform(post("/api/reviews").contentType("application/json").content("{}")).andExpect(status().isForbidden());
  mvc.perform(get("/api/reviews/mine")).andExpect(status().isUnauthorized());
  perform(seller,get("/api/reviews/mine")).andExpect(status().isForbidden());perform(buyer,get("/api/seller/reviews")).andExpect(status().isForbidden());
  mvc.perform(post("/api/reviews").cookie(buyer.cookie()).contentType("application/json").content(json.writeValueAsString(input(5,"x")))).andExpect(status().isForbidden());
  long id=create();perform(other,delete("/api/reviews/"+id)).andExpect(status().isNotFound());
  perform(other,put("/api/reviews/"+id).contentType("application/json").content(json.writeValueAsString(input(1,"x")))).andExpect(status().isNotFound());
  perform(other,get("/api/reviews/mine")).andExpect(content().json("[]"));assertThat(service.summary(product).average()).isEqualTo(5);
 }
 @Test void validatesRatingAndOptionalCommentWithoutPartialData() throws Exception {
  for(Integer rating:Arrays.asList(null,0,6,-1))perform(buyer,post("/api/reviews").contentType("application/json").content(json.writeValueAsString(input(rating,"x")))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REVIEW_RATING"));
  perform(buyer,post("/api/reviews").contentType("application/json").content(json.writeValueAsString(input(5,"x".repeat(2001))))).andExpect(status().isBadRequest());
  assertThatThrownBy(()->service.create(account,null,List.of())).isInstanceOf(RuntimeException.class);assertThat(count("product_reviews")).isZero();
 }
 @Test void missingProductsAndPurchaseIdentifiersAreRejected() {
  assertThatThrownBy(()->service.create(account,new ReviewService.Input(null,product,5,"",null),List.of())).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->service.create(account,new ReviewService.Input(order,Long.MAX_VALUE,5,"",null),List.of())).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->service.product(Long.MAX_VALUE,0)).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->service.delete(account,Long.MAX_VALUE)).isInstanceOf(RuntimeException.class);
 }
 @Test void duplicatePublicationAndSpoofedPurchaseCannotChangeTheOriginal() {
  long id=create();assertThat(service.eligibility(account,order,product).review().id()).isEqualTo(id);
  assertThatThrownBy(()->create()).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->service.edit(account,id,new ReviewService.Input(order+1,product,1,"spoofed",null),List.of())).isInstanceOf(RuntimeException.class);
  assertThat(service.mine(account,0)).hasSize(1);assertThat(service.mine(account,0).getFirst().rating()).isEqualTo(5);
 }
 @Test void concurrentPublicationCreatesOnlyOneReview() throws Exception {
  try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
   var tasks=new ArrayList<java.util.concurrent.Future<Boolean>>();for(int i=0;i<2;i++) tasks.add(pool.submit(()->{try{create();return true;}catch(RuntimeException rejected){return false;}}));
   assertThat(tasks.get(0).get()^tasks.get(1).get()).isTrue();
  }assertThat(count("product_reviews")).isEqualTo(1);
 }
 @Test void deletedReviewCanBeRepublishedWithoutOldImagesOrResponse() throws Exception {
  long id=service.create(account,input(3,"Original"),List.of(png())).id();service.reply(1,jdbc.queryForObject("SELECT owner_account_id FROM stores WHERE id=1",Long.class),id,"Respuesta",false);
  service.delete(account,id);assertThat(count("product_review_images")).isZero();
  assertThat(service.create(account,input(4,"Nueva"),List.of()).id()).isEqualTo(id);assertThat(service.product(product,0).getFirst().reply()).isNull();
 }
 @Test void multipartImagesAreStoredValidatedServedAndCanBeRemoved() throws Exception {
  var result=upload(buyer,multipart("/api/reviews").file(body(input(4,"Fotos"))).file(new MockMultipartFile("images","foto.png","image/png",png()))).andExpect(status().isOk()).andReturn();
  var review=json.readTree(result.getResponse().getContentAsString());long id=review.get("id").asLong(),image=review.get("images").get(0).asLong();
  var data=mvc.perform(get("/api/reviews/images/"+image)).andExpect(status().isOk()).andExpect(header().string("Content-Type","image/png")).andReturn();
  mvc.perform(get("/api/reviews/images/"+image).header("If-None-Match",data.getResponse().getHeader("ETag"))).andExpect(status().isNotModified());
  var edited=new ReviewService.Input(order,product,5,"Nueva foto",List.of());
  upload(buyer,multipart("/api/reviews/"+id).file(body(edited)).file(new MockMultipartFile("images","nueva.png","image/png",png())).with(r->{r.setMethod("PUT");return r;})).andExpect(status().isOk());
  mvc.perform(get("/api/reviews/images/"+image)).andExpect(status().isNotFound());
  service.edit(account,id,new ReviewService.Input(order,product,2,"Sin fotos",List.of()),List.of());assertThat(count("product_review_images")).isZero();
 }
 @Test void invalidFilesLeaveNoReviewOrImages() throws Exception {
  upload(buyer,multipart("/api/reviews").file(body(input(3,"No guardar"))).file(new MockMultipartFile("images","fake.png","image/png","<svg>bad</svg>".getBytes()))).andExpect(status().isBadRequest());
  assertThat(count("product_reviews")).isZero();assertThat(count("product_review_images")).isZero();
  assertThatThrownBy(()->service.create(account,new ReviewService.Input(order,product,4,"",List.of(90L)),List.of())).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->service.create(account,input(3,""),List.of(png(),png(),png(),png()))).isInstanceOf(RuntimeException.class);
 }
 @Test void editingCannotAttachAnotherReviewsImagesOrExceedLimit() throws Exception {
  var r=service.create(account,input(5,"Original"),List.of(png(),png(),png()));
  assertThatThrownBy(()->service.edit(account,r.id(),new ReviewService.Input(order,product,1,"x",List.of(999L)),List.of())).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->service.edit(account,r.id(),new ReviewService.Input(order,product,1,"x",List.of(r.images().getFirst(),r.images().getFirst())),List.of())).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->service.edit(account,r.id(),input(1,"x"),List.of(png()))).isInstanceOf(RuntimeException.class);
  assertThat(service.mine(account,0).getFirst().comment()).isEqualTo("Original");
 }
 @Test void sellerCanFilterReplyEditDeleteAndViewOnlyMinimalOwnPurchase() throws Exception {
  long id=create();perform(seller,get("/api/seller/reviews").param("rating","5").param("productId",""+product).param("answered","false")).andExpect(jsonPath("$[0].id").value(id));
  perform(seller,get("/api/seller/reviews/"+id+"/purchase")).andExpect(jsonPath("$.orderId").value(order)).andExpect(jsonPath("$.recipientName").doesNotExist()).andExpect(jsonPath("$.accountId").doesNotExist()).andExpect(jsonPath("$.phone").doesNotExist());
  perform(seller,put("/api/seller/reviews/"+id+"/reply").contentType("application/json").content("{\"text\":\"Gracias\"}")).andExpect(status().isOk());
  perform(seller,put("/api/seller/reviews/"+id+"/reply").contentType("application/json").content("{\"text\":\"Gracias de nuevo\"}")).andExpect(status().isOk());
  perform(seller,get("/api/seller/reviews").param("answered","true")).andExpect(jsonPath("$[0].reply.text").value("Gracias de nuevo"));
  perform(seller,get("/api/seller/reviews/summary")).andExpect(jsonPath("$[0].average").value(5.0));
  perform(seller,delete("/api/seller/reviews/"+id+"/reply")).andExpect(status().isOk());assertThat(service.product(product,0).getFirst().reply()).isNull();
 }
 @Test void anotherSellerCannotSeePurchaseOrManageReply() throws Exception {
  long id=create();perform(outsider,get("/api/seller/reviews")).andExpect(content().json("[]"));
  perform(outsider,get("/api/seller/reviews/"+id+"/purchase")).andExpect(status().isNotFound());
  perform(outsider,put("/api/seller/reviews/"+id+"/reply").contentType("application/json").content("{\"text\":\"No\"}")).andExpect(status().isNotFound());
  perform(outsider,delete("/api/seller/reviews/"+id+"/reply")).andExpect(status().isNotFound());
  assertThat(count("product_review_replies")).isZero();
 }
 @Test void transferredStoreCannotEditPreviousActorsReply() {
  long id=create();long original=jdbc.queryForObject("SELECT owner_account_id FROM stores WHERE id=1",Long.class);service.reply(1,original,id,"Original",false);
  assertThatThrownBy(()->service.reply(1,original+100,id,"Otro",false)).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->service.reply(1,original+100,id,null,true)).isInstanceOf(RuntimeException.class);
  assertThat(service.seller(1,original+100,null,null,null,0).getFirst().canManageReply()).isFalse();
 }
 @Test void invalidReplyAndFiltersAreRejected() throws Exception {
  long id=create();for(String value:Arrays.asList(null," ","x".repeat(2001)))assertThatThrownBy(()->service.reply(1,1,id,value,false)).isInstanceOf(RuntimeException.class);
  for(int page:new int[]{-1,10001}) {assertThatThrownBy(()->service.mine(account,page)).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->service.product(product,page)).isInstanceOf(RuntimeException.class);}
  perform(seller,get("/api/seller/reviews").param("rating","6")).andExpect(status().isBadRequest());assertThat(count("product_review_replies")).isZero();
 }
 @Test void multipleBuyersProduceCorrectDistributionAndAverages() throws Exception {
  create();long another=jdbc.queryForObject("SELECT id FROM user_accounts WHERE email='review-other@test.example'",Long.class);
  long anotherOrder=seedOrder(1,"DELIVERED",product,1,"100");jdbc.update("UPDATE orders SET account_id=? WHERE id=?",another,anotherOrder);
  service.create(another,new ReviewService.Input(anotherOrder,product,1,"",null),List.of());
  assertThat(service.summary(product).average()).isEqualTo(3);assertThat(service.summary(product).distribution()).containsExactly(1L,0L,0L,0L,1L);
  mvc.perform(get("/api/products")).andExpect(jsonPath("$[0].reviewCount").value(2));
  assertThat(service.summaries(null,2L)).isEmpty();
 }
 @Test void reportTargetsExactReviewAndModerationHidesReviewImageAndRating() throws Exception {
  var r=service.create(account,input(5,"Reseña reportada"),List.of(png()));hidden(r.id(),ReportContentType.RESENA);
  assertThat(service.product(product,0)).isEmpty();assertThat(service.summary(product).count()).isZero();
  mvc.perform(get("/api/reviews/images/"+r.images().getFirst())).andExpect(status().isNotFound());
  assertThat(jdbc.queryForObject("SELECT content_id FROM reports",String.class)).isEqualTo(r.id().toString());
 }
 @Test void replyCanBeReportedAndHiddenIndependently() throws Exception {
  long id=create();service.reply(1,jdbc.queryForObject("SELECT owner_account_id FROM stores WHERE id=1",Long.class),id,"Respuesta",false);
  hidden(id,ReportContentType.RESPUESTA_RESENA);assertThat(service.product(product,0)).hasSize(1);assertThat(service.product(product,0).getFirst().reply()).isNull();assertThat(service.summary(product).count()).isEqualTo(1);
 }
 @Test void adaptersResolveOwnersSnapshotsAndRejectInvalidIds() {
  long id=create();service.reply(1,jdbc.queryForObject("SELECT owner_account_id FROM stores WHERE id=1",Long.class),id,"Respuesta",false);
  for(var type:List.of(ReportContentType.RESENA,ReportContentType.RESPUESTA_RESENA)){
   var adapter=(ReviewContentConfiguration.Adapter)snapshots.stream().filter(p->p.type()==type).findFirst().orElseThrow();
   assertThat(adapter.load(Long.toString(id))).isPresent();assertThat(adapter.ownerAccountId(Long.toString(id))).isPresent();
   assertThat(adapter.isAccessibleTo(Long.toString(id),"999")).isTrue();
   for(String invalid:Arrays.asList(null,"abc","-1","99999999")){assertThat(adapter.load(invalid)).isEmpty();assertThat(adapter.isAccessibleTo(invalid,"999")).isFalse();}
  }
 }
 @Test void unpublishedOrRemovedProductDoesNotExposeReviewsOrImages() throws Exception {
  var r=service.create(account,input(5,"x"),List.of(png()));jdbc.update("UPDATE products SET status='DRAFT' WHERE id=?",product);
  assertThatThrownBy(()->service.product(product,0)).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->service.image(r.images().getFirst())).isInstanceOf(RuntimeException.class);
  service.delete(account,r.id());assertThatThrownBy(()->service.image(r.images().getFirst())).isInstanceOf(RuntimeException.class);
 }
 @Test void deletedReviewKeepsModerationSnapshotButCannotReceiveNewReports() {
  long id=create();
  var adapter=(ReviewContentConfiguration.Adapter)snapshots.stream().filter(p->p.type()==ReportContentType.RESENA).findFirst().orElseThrow();
  service.delete(account,id);
  assertThat(adapter.load(Long.toString(id))).isPresent();
  assertThat(adapter.isAccessibleTo(Long.toString(id),"999")).isFalse();
 }
}
