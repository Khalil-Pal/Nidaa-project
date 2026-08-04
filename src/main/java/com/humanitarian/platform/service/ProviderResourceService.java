package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.ProviderCapacityAssessment;
import com.humanitarian.platform.dto.ProviderResourceDto;
import com.humanitarian.platform.dto.ProviderResourceResponse;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.ProviderResource;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.ProviderResourceRepository;
import com.humanitarian.platform.util.HelpTypeNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProviderResourceService {

    private static final Set<String> SUPPORTED_HELP_TYPES = Set.of(
            "MEDICAL", "FOOD", "SHELTER", "WATER", "CLOTHING");
    private static final Set<UserRole> PROVIDER_ROLES = Set.of(
            UserRole.VOLUNTEER, UserRole.ORGANIZATION);

    private final ProviderResourceRepository providerResourceRepository;
    private final UserService userService;

    public ProviderResourceService(ProviderResourceRepository providerResourceRepository,
                                   UserService userService) {
        this.providerResourceRepository = providerResourceRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public List<ProviderResourceResponse> getMyResources() {
        User currentUser = requireProviderUser();
        return providerResourceRepository.findByUserId(currentUser.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ProviderResourceResponse upsertResource(ProviderResourceDto request) {
        User currentUser = requireProviderUser();
        String helpType = requireSupportedHelpType(request.getHelpType());
        String mode = requireCapacityMode(request.getCapacityMode());
        validateCapacity(request, mode);

        ProviderResource resource = providerResourceRepository
                .findByUserIdAndHelpType(currentUser.getId(), helpType)
                .orElseGet(() -> ProviderResource.builder()
                        .userId(currentUser.getId())
                        .helpType(helpType)
                        .build());

        resource.setCapacityMode(mode);
        if ("NUMERIC".equals(mode)) {
            resource.setCapacityAmount(request.getCapacityAmount());
            resource.setCapacityLabel(null);
        } else {
            resource.setCapacityAmount(null);
            resource.setCapacityLabel(request.getCapacityLabel().trim());
        }

        return toResponse(providerResourceRepository.save(resource));
    }

    @Transactional
    public void deleteResource(String rawHelpType) {
        User currentUser = requireProviderUser();
        String helpType = requireSupportedHelpType(rawHelpType);
        ProviderResource resource = providerResourceRepository
                .findByUserIdAndHelpType(currentUser.getId(), helpType)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Provider resource not found for help type: " + helpType));
        providerResourceRepository.delete(resource);
    }

    @Transactional(readOnly = true)
    public Set<Long> findEligibleProviderUserIds(String rawHelpType) {
        return findEligibleProviderCapacityAssessments(rawHelpType, null).keySet();
    }

    @Transactional(readOnly = true)
    public Map<Long, ProviderCapacityAssessment> findEligibleProviderCapacityAssessments(
            String rawHelpType,
            Integer peopleCount) {
        String helpType = HelpTypeNormalizer.normalize(rawHelpType);
        if (!SUPPORTED_HELP_TYPES.contains(helpType)) {
            return Map.of();
        }

        return providerResourceRepository.findByHelpType(helpType).stream()
                .filter(this::hasUsableCapacity)
                .collect(Collectors.toUnmodifiableMap(
                        ProviderResource::getUserId,
                        resource -> toCapacityAssessment(resource, peopleCount),
                        (first, ignored) -> first));
    }

    @Transactional(readOnly = true)
    public ProviderCapacityAssessment requireUsableResource(Long userId,
                                                             String rawHelpType) {
        return requireUsableResource(userId, rawHelpType, null);
    }

    @Transactional(readOnly = true)
    public ProviderCapacityAssessment requireUsableResource(Long userId,
                                                             String rawHelpType,
                                                             Integer peopleCount) {
        String helpType = HelpTypeNormalizer.normalize(rawHelpType);
        ProviderResource resource = SUPPORTED_HELP_TYPES.contains(helpType)
                ? providerResourceRepository.findByUserIdAndHelpType(userId, helpType)
                .filter(this::hasUsableCapacity)
                .orElse(null)
                : null;

        if (resource == null) {
            throw new BusinessException(
                    "Your provider profile does not list an available "
                            + helpType + " resource for this request.");
        }
        return toCapacityAssessment(resource, peopleCount);
    }

    private User requireProviderUser() {
        User currentUser = userService.getCurrentUser();
        if (currentUser.getRole() == null || !PROVIDER_ROLES.contains(currentUser.getRole())) {
            throw new UnauthorizedException(
                    "Only volunteers and organizations can manage provider resources.");
        }
        return currentUser;
    }

    private String requireSupportedHelpType(String rawHelpType) {
        String helpType = HelpTypeNormalizer.normalize(rawHelpType);
        if (!SUPPORTED_HELP_TYPES.contains(helpType)) {
            throw new BusinessException(
                    "Help type must be one of: MEDICAL, FOOD, SHELTER, WATER, CLOTHING.");
        }
        return helpType;
    }

    private String requireCapacityMode(String rawMode) {
        String mode = rawMode == null ? "" : rawMode.trim().toUpperCase(Locale.ROOT);
        if (!"NUMERIC".equals(mode) && !"QUALITATIVE".equals(mode)) {
            throw new BusinessException("Capacity mode must be NUMERIC or QUALITATIVE.");
        }
        return mode;
    }

    private void validateCapacity(ProviderResourceDto request, String mode) {
        if ("NUMERIC".equals(mode)
                && (request.getCapacityAmount() == null || request.getCapacityAmount() <= 0)) {
            throw new BusinessException("Amount must be greater than zero for NUMERIC capacity.");
        }
        if ("QUALITATIVE".equals(mode)) {
            if (request.getCapacityLabel() == null || request.getCapacityLabel().isBlank()) {
                throw new BusinessException("Label is required for QUALITATIVE capacity.");
            }
            if (request.getCapacityLabel().trim().length() > 50) {
                throw new BusinessException("Label must not exceed 50 characters.");
            }
        }
    }

    private boolean hasUsableCapacity(ProviderResource resource) {
        if ("NUMERIC".equals(resource.getCapacityMode())) {
            return resource.getCapacityAmount() != null && resource.getCapacityAmount() > 0;
        }
        if ("QUALITATIVE".equals(resource.getCapacityMode())) {
            return resource.getCapacityLabel() != null && !resource.getCapacityLabel().isBlank();
        }
        return false;
    }

    private ProviderCapacityAssessment toCapacityAssessment(ProviderResource resource,
                                                             Integer peopleCount) {
        Boolean sufficient = null;
        if ("NUMERIC".equals(resource.getCapacityMode())
                && peopleCount != null && peopleCount > 0) {
            sufficient = resource.getCapacityAmount() >= peopleCount;
        }
        return ProviderCapacityAssessment.builder()
                .userId(resource.getUserId())
                .capacityMode(resource.getCapacityMode())
                .capacityAmount(resource.getCapacityAmount())
                .capacitySufficient(sufficient)
                .build();
    }

    private ProviderResourceResponse toResponse(ProviderResource resource) {
        return ProviderResourceResponse.builder()
                .helpType(resource.getHelpType())
                .capacityMode(resource.getCapacityMode())
                .capacityAmount(resource.getCapacityAmount())
                .capacityLabel(resource.getCapacityLabel())
                .build();
    }
}
