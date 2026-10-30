package com.careslot.prescribing.application;

import com.careslot.prescribing.domain.PrescriptionItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RxContentTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PATIENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant AT = Instant.parse("2026-10-30T10:00:00Z");
    private static final List<PrescriptionItem> ITEMS = List.of(new PrescriptionItem("RX-PARA", "500 mg", "TDS", 5));

    @Test
    void canonicalFormIsStableAndCoversEveryField() {
        String c = RxContent.canonical(ID, PATIENT, "dr-a", AT, ITEMS, null);
        assertThat(c).startsWith("careslot-rx-v1\n").contains("1|RX-PARA|500 mg|TDS|5");
        String hash = RxContent.sha256(c);
        assertThat(hash).hasSize(64).isEqualTo(RxContent.sha256(RxContent.canonical(ID, PATIENT, "dr-a", AT, ITEMS, null)));

        assertThat(RxContent.sha256(RxContent.canonical(ID, PATIENT, "dr-a", AT, ITEMS, "a reason long enough")))
                .isNotEqualTo(hash);
        assertThat(RxContent.sha256(RxContent.canonical(ID, PATIENT, "dr-a", AT,
                List.of(new PrescriptionItem("RX-PARA", "500 mg", "TDS", 6)), null))).isNotEqualTo(hash);
        assertThat(RxContent.sha256(RxContent.canonical(ID, PATIENT, "dr-b", AT, ITEMS, null))).isNotEqualTo(hash);
    }
}
