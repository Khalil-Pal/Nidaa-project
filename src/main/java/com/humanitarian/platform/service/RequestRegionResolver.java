package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class RequestRegionResolver {

    public String resolve(HelpRequest request) {
        if (request.getLatitude() != null && request.getLongitude() != null) {
            int latitudeBand = (int) Math.floor(request.getLatitude());
            int longitudeBand = (int) Math.floor(request.getLongitude());
            return String.format(Locale.ROOT, "GEO_%+03d_%+04d", latitudeBand, longitudeBand);
        }

        if (request.getAddress() != null && !request.getAddress().isBlank()) {
            String[] parts = request.getAddress().split(",");
            String region = parts[parts.length - 1].trim();
            if (!region.isBlank()) {
                return "ADDRESS_" + region.toUpperCase(Locale.ROOT);
            }
        }

        return "UNKNOWN";
    }
}
