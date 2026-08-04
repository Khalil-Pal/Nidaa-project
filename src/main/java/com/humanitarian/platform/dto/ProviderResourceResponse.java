package com.humanitarian.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderResourceResponse {
    private String helpType;
    private String capacityMode;
    private Integer capacityAmount;
    private String capacityLabel;
}
