package com.transformersas.marketplace.orders;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.orders.domain.model.*;
import com.transformersas.marketplace.orders.domain.repository.OrderCancellationRepository;
import com.transformersas.marketplace.orders.domain.repository.OrderIssueRepository;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

class CancellationPersistenceIntegrationTests extends AbstractIntegrationTest {
    @Autowired OrderCancellationRepository cancellations;
    @Autowired OrderIssueRepository issues;
    @Test void cancellationRoundTripAndDatabaseUniquenessPreserveOriginalReason() {
        long order=seedOrder(1,"CONFIRMED",seedProduct(1,"Coffee",10,"20"),1,"20");
        var at=LocalDateTime.now().withNano(0);
        assertThat(cancellations.findByOrderId(order)).isEmpty();
        var record=new OrderCancellation(null,order,CancellationInitiator.BUYER,CancellationReason.OTHER,"Wrong size",null,"corr-db",at);
        cancellations.insert(record);
        var saved=cancellations.findByOrderId(order).orElseThrow();
        assertThat(saved).usingRecursiveComparison().ignoringFields("id").isEqualTo(record);
        assertThat(saved.id()).isPositive();
        assertThatThrownBy(()->cancellations.insert(record)).isInstanceOf(BusinessException.class).hasMessageContaining("ya fue cancelado");
        assertThat(cancellations.findByOrderId(order)).contains(saved);
        assertThat(count("order_cancellations")).isEqualTo(1);
    }
    @Test void resolvingIssueUsesCompareAndSetAndPreservesAuditIdentity() {
        long order=seedOrder(1,"IN_PREPARATION",seedProduct(1,"Coffee",10,"20"),1,"20");
        var issue=issues.register(new OrderIssue(null,order,IssueType.INVENTORY_INCONSISTENCY,"Mismatch",IssueStatus.OPEN,
                ActorType.SELLER,12L,LocalDateTime.now().withNano(0),null,null)).issue();
        assertThat(issues.findOpenByOrderId(order)).hasSize(1);
        assertThat(issues.resolve(issue.id(),13L,LocalDateTime.now().withNano(0))).isTrue();
        assertThat(issues.resolve(issue.id(),14L,LocalDateTime.now())).isFalse();
        assertThat(issues.findOpenByOrderId(order)).isEmpty();
        var saved=issues.findByIdAndOrderId(issue.id(),order).orElseThrow();
        assertThat(saved.status()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(saved.resolvedById()).isEqualTo(13L);
        assertThat(issues.findByOrderId(order)).containsExactly(saved);
    }
}
