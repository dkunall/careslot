package com.careslot.prescribing.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses a made-up three-class catalog; the point is the rule mechanics, not pharmacology. */
class SafetyRuleTest {

    private final DrugCatalog catalog = new DrugCatalog(List.of(
            new Drug("D-ANTICOAG", "Anticoagulant X", "ANTICOAGULANT"),
            new Drug("D-NSAID1", "Painkiller A", "NSAID"),
            new Drug("D-NSAID2", "Painkiller B", "NSAID"),
            new Drug("D-PEN", "Penicillin P", "PENICILLIN"),
            new Drug("D-PPI", "Antacid Q", "PPI")),
            List.of(new Interaction("D-ANTICOAG", "D-NSAID1", Severity.SEVERE, "bleeding risk"),
                    new Interaction("D-PPI", "D-NSAID2", Severity.MINOR, "sample minor note")));
    private final InteractionChecker checker = new InteractionChecker(SafetyRule.standard());

    private static PrescriptionItem item(String code) {
        return new PrescriptionItem(code, "1 tab", "BD", 5);
    }

    private static PatientContext ctx(Set<String> meds, Set<String> allergies) {
        return new PatientContext(meds, allergies, "test");
    }

    @Test
    void interactionWithACurrentMedicineIsSevere() {
        var r = checker.check(List.of(item("D-NSAID1")), ctx(Set.of("D-ANTICOAG"), Set.of()), catalog);
        assertThat(r.hasSevere()).isTrue();
        assertThat(r.findings()).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("interaction");
            assertThat(f.drugs()).containsExactlyInAnyOrder("D-NSAID1", "D-ANTICOAG");
        });
    }

    @Test
    void interactionBetweenTwoNewItemsIsReportedOnce() {
        var r = checker.check(List.of(item("D-PPI"), item("D-NSAID2")), ctx(Set.of(), Set.of()), catalog);
        assertThat(r.findings()).extracting(Finding::rule).containsExactly("interaction");
        assertThat(r.blocksSigning()).isFalse();
    }

    @Test
    void classAllergyBlocks() {
        var r = checker.check(List.of(item("D-PEN")), ctx(Set.of(), Set.of("class:PENICILLIN")), catalog);
        assertThat(r.findings()).extracting(Finding::rule, Finding::severity).containsExactly(
                org.assertj.core.groups.Tuple.tuple("allergy", Severity.SEVERE));
    }

    @Test
    void twoDrugsOfOneClassAreDuplicateTherapy() {
        var r = checker.check(List.of(item("D-NSAID1"), item("D-NSAID2")), ctx(Set.of(), Set.of()), catalog);
        assertThat(r.findings()).extracting(Finding::rule, Finding::severity).containsExactly(
                org.assertj.core.groups.Tuple.tuple("duplicate-therapy", Severity.MODERATE));
        var refill = checker.check(List.of(item("D-PPI")), ctx(Set.of("D-PPI"), Set.of()), catalog);
        assertThat(refill.findings()).extracting(Finding::severity).containsExactly(Severity.MINOR);
    }

    @Test
    void signingFailsClosedWithoutRecordsAndNeedsARealReasonToOverride() {
        var unchecked = SafetyResult.unchecked("records unavailable");
        assertThat(SignDecision.decide(unchecked, null)).isEqualTo(SignDecision.BLOCKED_UNCHECKED);
        assertThat(SignDecision.decide(unchecked, "urgent")).isEqualTo(SignDecision.OVERRIDE_REASON_TOO_SHORT);
        assertThat(SignDecision.decide(unchecked, "records down, patient confirmed no other meds"))
                .isEqualTo(SignDecision.ALLOWED_WITH_OVERRIDE);
        var clean = checker.check(List.of(item("D-PPI")), ctx(Set.of(), Set.of()), catalog);
        assertThat(SignDecision.decide(clean, null)).isEqualTo(SignDecision.ALLOWED);
    }

    @Test
    void builderRejectsUnknownDrugsAndEmptyPrescriptions() {
        var b = new PrescriptionBuilder(catalog).patient(UUID.randomUUID()).prescriber("dr");
        assertThatThrownBy(() -> b.item(item("D-NOPE"))).hasMessageContaining("unknown drug");
        assertThatThrownBy(b::build).hasMessageContaining("1..");
        assertThatThrownBy(() -> new PrescriptionItem("D-PPI", "1", "THRICE", 3)).hasMessageContaining("frequency");
    }

    @Test
    void catalogRejectsInteractionsWithUnknownDrugs() {
        assertThatThrownBy(() -> new DrugCatalog(List.of(new Drug("A", "a", "X")),
                List.of(new Interaction("A", "B", Severity.SEVERE, "")))).isInstanceOf(IllegalArgumentException.class);
    }
}
