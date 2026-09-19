package com.transformersas.marketplace.reports.infrastructure.persistence.entity;

import com.transformersas.marketplace.reports.domain.model.ReportContentType;

import java.io.Serializable;
import java.util.Objects;

public class ContentModerationStateId implements Serializable {

    private ReportContentType contentType;
    private String contentId;

    public ContentModerationStateId() {
    }

    public ContentModerationStateId(ReportContentType contentType, String contentId) {
        this.contentType = contentType;
        this.contentId = contentId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ContentModerationStateId id
                && contentType == id.contentType && Objects.equals(contentId, id.contentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contentType, contentId);
    }
}
