package com.transformersas.marketplace.restrictions.application.dto;

import com.transformersas.marketplace.restrictions.domain.model.AccountRecord;
import com.transformersas.marketplace.restrictions.domain.port.AccountDirectoryEntry;

import java.util.List;

/** Antecedentes y situación de una cuenta para evaluarla (pasos 4 a 6, 19). */
public record AccountEvaluation(AccountDirectoryEntry account, List<AccountRecord> records, long recentRecords,
                                boolean recidivist, List<RestrictionView> activeRestrictions,
                                List<RestrictionView> restrictions,
                                List<InformationRequestView> informationRequests) {
}
