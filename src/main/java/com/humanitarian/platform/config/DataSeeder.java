package com.humanitarian.platform.config;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.service.PriorityScoreService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component
@Profile("dev")
public class DataSeeder implements CommandLineRunner {

    private static final String[] TYPES = {"MEDICAL", "FOOD", "SHELTER", "WATER", "CLOTHING"};
    private static final double[][] CLUSTERS = {
            {55.75, 37.62},
            {59.93, 30.32},
            {56.85, 60.61}
    };

    private final HelpRequestRepository helpRequestRepository;
    private final UserRepository userRepository;
    private final PriorityScoreService priorityScoreService;

    public DataSeeder(HelpRequestRepository helpRequestRepository,
                      UserRepository userRepository,
                      PriorityScoreService priorityScoreService) {
        this.helpRequestRepository = helpRequestRepository;
        this.userRepository = userRepository;
        this.priorityScoreService = priorityScoreService;
    }

    @Override
    public void run(String... args) {
        if (helpRequestRepository.count() > 0) {
            return;
        }

        Long beneficiaryId = userRepository.findByRole(UserRole.BENEFICIARY).stream()
                .findFirst()
                .map(User::getId)
                .or(() -> userRepository.findAll().stream().findFirst().map(User::getId))
                .orElse(null);

        if (beneficiaryId == null) {
            System.out.println("DataSeeder: skipped sample help requests because no users exist.");
            return;
        }

        Random random = new Random(42);
        List<HelpRequest> requests = new ArrayList<>();

        for (int i = 0; i < 500; i++) {
            double[] cluster = CLUSTERS[random.nextInt(CLUSTERS.length)];
            String urgency = weightedUrgency(random);

            HelpRequest request = HelpRequest.builder()
                    .beneficiaryId(beneficiaryId)
                    .title("Seeded " + urgency.toLowerCase() + " request #" + i)
                    .helpType(TYPES[random.nextInt(TYPES.length)])
                    .urgencyLevel(urgency)
                    .description("Seeded request #" + i)
                    .peopleCount(random.nextInt(10) + 1)
                    .hasChildren(random.nextFloat() < 0.30f)
                    .hasElderly(random.nextFloat() < 0.20f)
                    .hasDisabled(random.nextFloat() < 0.15f)
                    .latitude(cluster[0] + (random.nextDouble() - 0.5) * 0.5)
                    .longitude(cluster[1] + (random.nextDouble() - 0.5) * 0.5)
                    .status("PENDING")
                    .createdAt(LocalDateTime.now().minusHours(random.nextInt(168)))
                    .build();

            request.setPriorityScore(priorityScoreService.calculate(request));
            requests.add(request);
        }

        helpRequestRepository.saveAll(requests);
        System.out.println("DataSeeder: inserted 500 sample help requests.");
    }

    private String weightedUrgency(Random random) {
        int value = random.nextInt(100);
        if (value < 10) {
            return "CRITICAL";
        }
        if (value < 35) {
            return "HIGH";
        }
        if (value < 75) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
