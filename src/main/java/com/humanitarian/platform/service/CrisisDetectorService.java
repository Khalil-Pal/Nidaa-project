package com.humanitarian.platform.service;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

@Service
public class CrisisDetectorService {

    private static final Set<String> CRISIS_KEYWORDS = Set.of(
            "суицид",
            "самоубийство",
            "не хочу жить",
            "кризис",
            "опасность",
            "срочно",
            "помогите",
            "انتحار",
            "أريد أن أموت",
            "لا أريد أن أعيش",
            "أؤذي نفسي",
            "حالة طارئة",
            "أزمة",
            "ساعدني",
            "crisis",
            "suicide",
            "suicidal",
            "kill myself",
            "end my life",
            "don't want to live",
            "self harm",
            "self-harm",
            "emergency",
            "urgent"
    );

    public boolean detect(String category, String description) {
        String normalizedCategory = category == null
                ? ""
                : category.toUpperCase().trim().replace(" ", "_").replace("-", "_");
        if ("CRISIS_SUPPORT".equals(normalizedCategory) || "CRISIS".equals(normalizedCategory)) {
            return true;
        }
        if (description == null) {
            return false;
        }
        String lower = description.toLowerCase(Locale.ROOT);
        return CRISIS_KEYWORDS.stream()
                .anyMatch(keyword -> lower.contains(keyword.toLowerCase(Locale.ROOT)));
    }
}
