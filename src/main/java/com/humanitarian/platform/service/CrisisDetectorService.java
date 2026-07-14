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
        if ("CRISIS_SUPPORT".equalsIgnoreCase(category)) {
            return true;
        }
        if (description == null) {
            return false;
        }
        String lower = description.toLowerCase();
        return CRISIS_KEYWORDS.stream().anyMatch(keyword -> lower.contains(keyword.toLowerCase()));
    }
}
