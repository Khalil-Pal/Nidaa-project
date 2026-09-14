package com.humanitarian.platform.persistence;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every label the services emit must be a label the database enums accept.
 * This is the test that would have caught D-1 (GRIEF_AND_LOSS and
 * DOMESTIC_VIOLENCE were emitted but did not exist).
 */
@EnabledIf(value = PersistenceTestSupport.CONDITION, disabledReason = "nidaa_test database not reachable")
class EnumRoundTripPersistenceTest extends PersistenceTestSupport {

    @ParameterizedTest
    @ValueSource(strings = {"ANXIETY", "DEPRESSION", "PTSD", "GRIEF", "VIOLENCE", "CRISIS", "CHILD", "OTHER"})
    void everyPsychologicalCategoryTheServiceEmitsPersists(String category) {
        User beneficiary = newUser(UserRole.BENEFICIARY, "cat-" + category.toLowerCase() + "@example.test");

        PsychologicalRequest saved = em.persistAndFlush(PsychologicalRequest.builder()
                .beneficiaryId(beneficiary.getId())
                .category(category)
                .supportType("INDIVIDUAL")
                .urgencyLevel("MEDIUM")
                .preferredFormat("CHAT")
                .description("round trip")
                .status("PENDING")
                .build());
        em.clear();

        assertEquals(category, em.find(PsychologicalRequest.class, saved.getId()).getCategory());
    }

    @Test
    void aCategoryThatIsNotAnEnumLabelIsRejectedByTheDatabase() {
        User beneficiary = newUser(UserRole.BENEFICIARY, "cat-bad@example.test");
        PsychologicalRequest bad = PsychologicalRequest.builder()
                .beneficiaryId(beneficiary.getId())
                .category("GRIEF_AND_LOSS")   // what the service emitted before D-1
                .supportType("INDIVIDUAL")
                .status("PENDING")
                .build();

        assertThrows(PersistenceException.class, () -> em.persistAndFlush(bad));
    }

    @ParameterizedTest
    @CsvSource({
            "MEDICAL,LOW", "MEDICAL,MEDIUM", "MEDICAL,HIGH", "MEDICAL,CRITICAL",
            "FOOD,LOW", "FOOD,MEDIUM", "FOOD,HIGH", "FOOD,CRITICAL",
            "SHELTER,LOW", "SHELTER,MEDIUM", "SHELTER,HIGH", "SHELTER,CRITICAL",
            "WATER,LOW", "WATER,MEDIUM", "WATER,HIGH", "WATER,CRITICAL",
            "CLOTHING,LOW", "CLOTHING,MEDIUM", "CLOTHING,HIGH", "CLOTHING,CRITICAL"
    })
    void everyHelpTypeAndUrgencyRoundTrips(String helpType, String urgency) {
        User beneficiary = newUser(UserRole.BENEFICIARY, ("rt-" + helpType + urgency + "@example.test").toLowerCase());

        HelpRequest saved = newHelpRequest(beneficiary.getId(), helpType, urgency, "PENDING");
        em.clear();
        HelpRequest reloaded = em.find(HelpRequest.class, saved.getId());

        assertEquals(helpType, reloaded.getHelpType());
        assertEquals(urgency, reloaded.getUrgencyLevel());
        assertNotNull(reloaded.getCreatedAt(), "created_at is filled by @CreationTimestamp");
    }

    @ParameterizedTest
    @CsvSource({"INDIVIDUAL,CHAT", "GROUP,AUDIO", "CRISIS,VIDEO"})
    void supportTypeAndFormatRoundTrip(String supportType, String format) {
        User beneficiary = newUser(UserRole.BENEFICIARY, ("fmt-" + supportType + format + "@example.test").toLowerCase());

        PsychologicalRequest saved = em.persistAndFlush(PsychologicalRequest.builder()
                .beneficiaryId(beneficiary.getId())
                .category("ANXIETY")
                .supportType(supportType)
                .urgencyLevel("MEDIUM")   // Hibernate writes every column, so the DB default never applies
                .preferredFormat(format)
                .status("PENDING")
                .build());
        em.clear();
        PsychologicalRequest reloaded = em.find(PsychologicalRequest.class, saved.getId());

        assertEquals(supportType, reloaded.getSupportType());
        assertEquals(format, reloaded.getPreferredFormat());
        assertEquals(false, reloaded.getNeedsReview(), "V14 default");
    }
}
