package com.transformersas.marketplace.reports;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ContentModerationStateId;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static org.assertj.core.api.Assertions.*;

class ContentModerationStateIdTests {
    @Test
    void compositeKeyDistinguishesTypeAndIdAndRetrievesEquivalentKeysFromMap() {
        var publication = new ContentModerationStateId(ReportContentType.PUBLICACION, "42");
        var same = new ContentModerationStateId(ReportContentType.PUBLICACION, "42");
        var review = new ContentModerationStateId(ReportContentType.RESENA, "42");
        var other = new ContentModerationStateId(ReportContentType.PUBLICACION, "43");
        var map = new HashMap<ContentModerationStateId, String>();
        map.put(publication, "hidden");
        map.put(review, "visible");
        assertThat(map.get(same)).isEqualTo("hidden");
        assertThat(map.get(review)).isEqualTo("visible");
        assertThat(map.get(other)).isNull();
        assertThat(publication).isNotEqualTo(null).isNotEqualTo("42").isNotEqualTo(review).isNotEqualTo(other);
        assertThat(new ContentModerationStateId()).isEqualTo(new ContentModerationStateId());
    }
}
