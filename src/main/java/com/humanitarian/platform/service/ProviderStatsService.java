package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.RatingSummary;
import com.humanitarian.platform.repository.ConsultationRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import com.humanitarian.platform.repository.ReportRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The four counter caches (AGG-1): {@code volunteers.total_completed_requests}
 * and {@code volunteers.rating} from the volunteer's completion reports (R-1),
 * {@code psychologists.consultation_count} and {@code psychologists.rating} from
 * the psychologist's consultation records (CS-1).
 *
 * Every refresh recomputes both values in full from the source rows, rather
 * than incrementing a count or averaging a new rating into the old one, so a
 * corrected or removed rating fixes the cache on the next refresh. The mean is
 * rounded to hundredths in Java, the precision of the {@code numeric(3,2)}
 * columns, so persisted equals computed. No ratings yet is {@code NULL}, never
 * 0 (D-3, V18).
 *
 * Called inside the transaction that wrote the source row, so the cache is
 * never observed ahead of, or behind, the data it summarises.
 */
@Service
public class ProviderStatsService {

    private static final Logger log = LoggerFactory.getLogger(ProviderStatsService.class);

    private final ReportRepository reportRepository;
    private final ConsultationRepository consultationRepository;
    private final VolunteerRepository volunteerRepository;
    private final PsychologistRepository psychologistRepository;

    public ProviderStatsService(ReportRepository reportRepository,
                                ConsultationRepository consultationRepository,
                                VolunteerRepository volunteerRepository,
                                PsychologistRepository psychologistRepository) {
        this.reportRepository = reportRepository;
        this.consultationRepository = consultationRepository;
        this.volunteerRepository = volunteerRepository;
        this.psychologistRepository = psychologistRepository;
    }

    @Transactional
    public void refreshVolunteer(Long volunteerId) {
        RatingSummary s = reportRepository.summarizeForVolunteer(volunteerId);
        int completed = count(s);
        Double rating = roundToHundredths(s == null ? null : s.mean());
        volunteerRepository.updateCounters(volunteerId, completed, rating);
        log.info("Volunteer {} counters refreshed: {} completed, rating {}", volunteerId, completed, rating);
    }

    @Transactional
    public void refreshPsychologist(Long psychologistId) {
        RatingSummary s = consultationRepository.summarizeForPsychologist(psychologistId);
        int consultations = count(s);
        Double rating = roundToHundredths(s == null ? null : s.mean());
        psychologistRepository.updateCounters(psychologistId, consultations, rating);
        log.info("Psychologist {} counters refreshed: {} consultations, rating {}", psychologistId, consultations, rating);
    }

    private static int count(RatingSummary s) {
        return s == null || s.count() == null ? 0 : s.count().intValue();
    }

    /** The stored precision is numeric(3,2); rounding here keeps the Java value equal to the column. */
    public static Double roundToHundredths(Double mean) {
        return mean == null ? null : Math.round(mean * 100.0) / 100.0;
    }
}
