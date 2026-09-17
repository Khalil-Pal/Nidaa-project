package com.humanitarian.platform.config;

import com.humanitarian.platform.evaluation.DatasetSpec;
import com.humanitarian.platform.evaluation.SyntheticDataGenerator;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.service.PriorityScoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Dev-profile sample data: 500 pending requests over the last week, spread over
 * three cities with the plan's urgency weighting, from a fixed seed. The
 * generation itself lives in {@link SyntheticDataGenerator}, which the matching
 * study (EV-1) parameterises for its own datasets; {@link DatasetSpec#demo} is
 * this seeder's preset.
 */
@Component
@Profile("dev")
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final HelpRequestRepository helpRequestRepository;
    private final UserRepository userRepository;
    private final PriorityScoreService priorityScoreService;
    private final SyntheticDataGenerator generator = new SyntheticDataGenerator();

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
            log.info("Skipped sample help requests because no users exist.");
            return;
        }

        List<HelpRequest> requests = generator.requests(DatasetSpec.demo(42, LocalDateTime.now()), beneficiaryId);
        requests.forEach(request -> request.setPriorityScore(priorityScoreService.calculate(request)));

        helpRequestRepository.saveAll(requests);
        log.info("Inserted {} sample help requests.", requests.size());
    }
}
