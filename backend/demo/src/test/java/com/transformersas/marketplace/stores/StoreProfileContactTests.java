package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF-059 (A3) y RF-060: contacto y horarios son opcionales y solo se validan si vienen. */
class StoreProfileContactTests {

    private static StoreProfile withContact(String email, String phone, String hours) {
        return new StoreProfile("Tienda", null, email, phone, hours);
    }

    private void assertInvalid(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.kind()).isEqualTo(BusinessException.Kind.INVALID);
            assertThat(error.code()).isEqualTo(code);
        });
    }

    @Test
    void contactAndHoursAreOptionalAndBlankBecomesNull() {
        StoreProfile blank = withContact("  ", "\t", "   ");

        assertThat(blank.contactEmail()).isNull();
        assertThat(blank.contactPhone()).isNull();
        assertThat(blank.businessHours()).isNull();
        assertThat(withContact(null, null, null)).isEqualTo(new StoreProfile("Tienda", null));
    }

    @Test
    void emailIsStrippedAndLowercased() {
        assertThat(withContact("  Ventas@Mi-Tienda.CO ", null, null).contactEmail()).isEqualTo("ventas@mi-tienda.co");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sin-arroba", "a@b", "a@@b.co", "a b@c.co", "@c.co", "a@.co", "a@c..co", "a@c."})
    void anInvalidEmailIsRejectedOnlyWhenPresent(String email) {
        assertInvalid(() -> withContact(email, null, null), "STORE_CONTACT_EMAIL_INVALID");
    }

    @Test
    void emailLengthIsBounded() {
        String longLocal = "a".repeat(StoreProfile.EMAIL_MAX - "@x.co".length());
        assertThat(withContact(longLocal + "@x.co", null, null).contactEmail()).hasSize(StoreProfile.EMAIL_MAX);
        assertInvalid(() -> withContact("a" + longLocal + "@x.co", null, null), "STORE_CONTACT_EMAIL_INVALID");
    }

    @Test
    void phoneAcceptsCommonFormatsAndCollapsesSpaces() {
        assertThat(withContact(null, "+57  300 123-4567", null).contactPhone()).isEqualTo("+57 300 123-4567");
        assertThat(withContact(null, "(601) 234.5678", null).contactPhone()).isEqualTo("(601) 234.5678");
        assertThat(withContact(null, "3001234567", null).contactPhone()).isEqualTo("3001234567");
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456", "1234567890123456", "abc1234567", "300-123-45x", "30+0123456", "++573001234567"})
    void aPhoneOutsideSevenToFifteenDigitsOrWithLettersIsRejected(String phone) {
        assertInvalid(() -> withContact(null, phone, null), "STORE_CONTACT_PHONE_INVALID");
    }

    @Test
    void phoneLengthIsBoundedEvenWithEnoughDigits() {
        assertInvalid(() -> withContact(null, "1234567" + "-".repeat(StoreProfile.PHONE_MAX), null),
                "STORE_CONTACT_PHONE_INVALID");
    }

    @Test
    void hoursKeepLineBreaksAndAreBounded() {
        assertThat(withContact(null, null, "  Lun-Vie 8-18\nSáb 9-13 ").businessHours())
                .isEqualTo("Lun-Vie 8-18\nSáb 9-13");
        assertThat(withContact(null, null, "h".repeat(StoreProfile.HOURS_MAX)).businessHours())
                .hasSize(StoreProfile.HOURS_MAX);
        assertInvalid(() -> withContact(null, null, "h".repeat(StoreProfile.HOURS_MAX + 1)), "STORE_HOURS_TOO_LONG");
        assertInvalid(() -> withContact(null, null, "Lun\u0007"), "STORE_HOURS_INVALID");
    }
}
