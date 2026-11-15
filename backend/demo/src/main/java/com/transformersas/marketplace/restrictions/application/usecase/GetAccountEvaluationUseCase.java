package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.application.dto.AccountEvaluation;
import com.transformersas.marketplace.restrictions.application.dto.InformationRequestView;
import com.transformersas.marketplace.restrictions.application.dto.RestrictionView;
import com.transformersas.marketplace.restrictions.domain.model.AccountRecord;
import com.transformersas.marketplace.restrictions.domain.model.AccountRestriction;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionChange;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionStatus;
import com.transformersas.marketplace.restrictions.domain.port.AccountDirectory;
import com.transformersas.marketplace.restrictions.domain.port.AccountDirectoryEntry;
import com.transformersas.marketplace.restrictions.domain.repository.AccountInformationRequestRepository;
import com.transformersas.marketplace.restrictions.domain.repository.AccountRecordQuery;
import com.transformersas.marketplace.restrictions.domain.repository.AccountRestrictionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Antecedentes y situación de una cuenta para evaluarla (pasos 4 a 6, RF-150, RF-151). Solo muestra lo necesario
 * para decidir: nada de información privada no relacionada (A9).
 */
@Service
public class GetAccountEvaluationUseCase {

    /** Antecedentes de los últimos 180 días cuentan como recientes, igual criterio que CU-27. */
    static final int RECENT_DAYS = 180;

    private final AccountDirectory accounts;
    private final AccountRecordQuery records;
    private final AccountRestrictionRepository restrictions;
    private final AccountInformationRequestRepository informationRequests;

    public GetAccountEvaluationUseCase(AccountDirectory accounts, AccountRecordQuery records,
                                       AccountRestrictionRepository restrictions,
                                       AccountInformationRequestRepository informationRequests) {
        this.accounts = accounts;
        this.records = records;
        this.restrictions = restrictions;
        this.informationRequests = informationRequests;
    }

    @Transactional(readOnly = true)
    public AccountEvaluation execute(Long accountId) {
        AccountDirectoryEntry account = accounts.findById(accountId)
                .orElseThrow(() -> BusinessException.notFound("ACCOUNT_NOT_FOUND", "La cuenta no existe"));
        LocalDateTime now = LocalDateTime.now();
        List<AccountRestriction> history = restrictions.findByAccount(accountId);
        List<AccountRecord> previousRestrictions = history.stream()
                .filter(restriction -> restriction.status() != RestrictionStatus.ACTIVE)
                .map(GetAccountEvaluationUseCase::toRecord)
                .toList();
        List<AccountRecord> allRecords = Stream.concat(records.confirmedStoreRecords(accountId).stream(),
                        previousRestrictions.stream())
                .sorted(Comparator.comparing(AccountRecord::decidedAt).reversed())
                .toList();
        long recent = recentRecords(allRecords, now.minusDays(RECENT_DAYS));
        boolean recidivist = recent >= 2 || !previousRestrictions.isEmpty();

        Map<Long, List<RestrictionChange>> changes = restrictions.findChanges(history.stream()
                .map(AccountRestriction::id).toList());
        List<RestrictionView> allViews = history.stream()
                .map(restriction -> new RestrictionView(restriction, changes.getOrDefault(restriction.id(),
                        List.of())))
                .toList();
        List<RestrictionView> active = allViews.stream().filter(view -> view.restriction().isActive()).toList();
        List<InformationRequestView> informationViews = informationRequests.findByAccount(accountId).stream()
                .map(request -> new InformationRequestView(request, request.effectiveStatus(now)))
                .toList();
        return new AccountEvaluation(account, allRecords, recent, recidivist, active, allViews, informationViews);
    }

    private static AccountRecord toRecord(AccountRestriction restriction) {
        String subject = restriction.role() == null ? "Toda la cuenta" : "Rol " + restriction.role();
        return new AccountRecord(AccountRecord.Type.RESTRICTION, restriction.id(), restriction.closedAt(), subject,
                restriction.status().name(), restriction.closeReason(), null);
    }

    /** Un antecedente con varias decisiones cuenta una sola vez, igual que en CU-27. */
    private static long recentRecords(List<AccountRecord> all, LocalDateTime since) {
        return all.stream()
                .filter(record -> !record.decidedAt().isBefore(since))
                .map(record -> record.type() + "-" + record.referenceId())
                .distinct()
                .count();
    }
}
