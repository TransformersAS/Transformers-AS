package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.restrictions.application.dto.AccountStanding;
import com.transformersas.marketplace.restrictions.application.dto.InformationRequestView;
import com.transformersas.marketplace.restrictions.application.dto.RestrictionView;
import com.transformersas.marketplace.restrictions.domain.repository.AccountInformationRequestRepository;
import com.transformersas.marketplace.restrictions.domain.repository.AccountRestrictionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Lo que la propia persona ve de su cuenta: sus restricciones vigentes y las solicitudes que puede responder. */
@Service
public class GetOwnAccountStandingUseCase {

    private final AccountRestrictionRepository restrictions;
    private final AccountInformationRequestRepository informationRequests;

    public GetOwnAccountStandingUseCase(AccountRestrictionRepository restrictions,
                                        AccountInformationRequestRepository informationRequests) {
        this.restrictions = restrictions;
        this.informationRequests = informationRequests;
    }

    @Transactional(readOnly = true)
    public AccountStanding execute(Long accountId) {
        LocalDateTime now = LocalDateTime.now();
        List<RestrictionView> active = restrictions.findActiveByAccount(accountId).stream()
                .map(restriction -> new RestrictionView(restriction, List.of()))
                .toList();
        List<InformationRequestView> informationViews = informationRequests.findByAccount(accountId).stream()
                .map(request -> new InformationRequestView(request, request.effectiveStatus(now)))
                .toList();
        return new AccountStanding(active, informationViews);
    }
}
