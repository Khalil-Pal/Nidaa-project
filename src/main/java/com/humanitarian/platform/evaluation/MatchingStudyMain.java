package com.humanitarian.platform.evaluation;

import com.humanitarian.platform.evaluation.MatchingStudy.Design;
import com.humanitarian.platform.evaluation.MatchingStudy.Run;
import com.humanitarian.platform.evaluation.MatchingStudy.Summary;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Runs the EV-1 matching study and writes the report. No Spring context and no
 * database: everything is generated in memory from the seed, so the run is
 * reproducible on any machine with the jar.
 *
 * <pre>
 * java -cp target/platform-1.0.0.jar \
 *      -Dloader.main=com.humanitarian.platform.evaluation.MatchingStudyMain \
 *      org.springframework.boot.loader.launch.PropertiesLauncher \
 *      --out docs/evaluation/ev-1 --reps 10 --seed 20260917
 * </pre>
 *
 * Re-running with the same seed and repetitions reproduces every file byte for
 * byte; the checksum printed at the end is the quick way to confirm it.
 */
public final class MatchingStudyMain {

    private MatchingStudyMain() {
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of("target/study");
        int reps = 10;
        long seed = 20260917L;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[i + 1]);
                case "--reps" -> reps = Integer.parseInt(args[i + 1]);
                case "--seed" -> seed = Long.parseLong(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        Design design = Design.full(reps, seed);
        long started = System.nanoTime();
        MatchingStudy study = new MatchingStudy();
        List<Run> runs = study.run(design, System.out::println);
        List<Summary> summaries = MatchingStudy.summarise(runs);
        StudyReport.write(out, design, runs, summaries);

        // The sensitivity analysis runs on sparse provision only: with a queue to
        // order, which is the only condition where the priority weights can matter.
        System.out.println(System.lineSeparator() + "sensitivity: the same datasets under " + MatchingStudy.WeightVariant.SENSITIVITY.size()
                + " weight variants");
        Design sparse = new Design(design.loads(), List.of(MatchingStudy.Density.SPARSE),
                design.repetitions(), design.baseSeed());
        StudyReport.writeSensitivity(out, study.sensitivity(sparse, MatchingStudy.WeightVariant.SENSITIVITY,
                System.out::println));
        double seconds = (System.nanoTime() - started) / 1e9;
        System.out.printf(Locale.ROOT, "%d runs (%d cells x %d repetitions) in %.1f s -> %s%n",
                runs.size(), summaries.size(), reps, seconds, out.toAbsolutePath());
        System.out.println("checksum " + checksum(summaries));
    }

    /** A digest of every cell mean, so two runs can be compared at a glance. */
    static String checksum(List<Summary> summaries) {
        long hash = 17;
        for (Summary s : summaries) {
            for (var ms : s.metrics().values()) {
                hash = 31 * hash + Double.hashCode(Math.round(ms.mean() * 1e6) / 1e6);
            }
        }
        return Long.toHexString(hash);
    }
}
