package com.humanitarian.platform.service;

import org.springframework.stereotype.Service;

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
            "СЃСѓРёС†РёРґ",
            "СЃР°РјРѕСѓР±РёР№СЃС‚РІРѕ",
            "РЅРµ С…РѕС‡Сѓ Р¶РёС‚СЊ",
            "crisis",
            "suicide",
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
        String lower = description.toLowerCase();
        return CRISIS_KEYWORDS.stream().anyMatch(keyword -> lower.contains(keyword.toLowerCase()));
    }
}
