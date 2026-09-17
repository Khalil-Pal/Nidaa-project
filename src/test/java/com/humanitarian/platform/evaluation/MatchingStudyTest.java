package com.humanitarian.platform.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.humanitarian.platform.evaluation.MatchingStudy.Density;
import com.humanitarian.platform.evaluation.MatchingStudy.Design;
import com.humanitarian.platform.evaluation.MatchingStudy.Load;
import com.humanitarian.platform.evaluation.MatchingStudy.Run;
import com.humanitarian.platform.evaluation.MatchingStudy.Summary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The design and the report on a reduced design (two repetitions): every cell is
 * filled, the same dataset reaches all four strategies, two runs with the same
 * seed agree to the last digit, and the report's files are complete. The full
 * study is run by {@code MatchingStudyMain}; its output is committed under
 * docs/evaluation/ev-1.
 */
class MatchingStudyTest {

    private static final Design SMALL = new Design(List.of(Load.LOW, Load.MEDIUM), List.of(Density.SPARSE), 2, 99L);

    /** One run of the reduced design, shared by the tests; the reproducibility test makes the second. */
    private static List<Run> first;

    private static synchronized List<Run> firstRun() {
        if (first == null) {
            first = new MatchingStudy().run(SMALL, line -> { });
        }
        return first;
    }

    @Test
    void everyCellIsRunOnTheSameSeededDatasetsAndSummarisedWithASampleSd() {
        List<Run> runs = firstRun();

        assertEquals(4 * 2 * 1 * 2, runs.size(), "strategies x loads x densities x repetitions");
        for (Load load : SMALL.loads()) {
            for (int rep = 0; rep < 2; rep++) {
                long seed = SMALL.seed(load, Density.SPARSE, rep);
                Set<String> strategiesOnThisSeed = runs.stream().filter(r -> r.seed() == seed)
                        .map(Run::strategy).collect(Collectors.toSet());
                assertEquals(Set.copyOf(StudyStrategies.NAMES), strategiesOnThisSeed, "paired: one dataset, four strategies");
            }
        }
        List<Summary> summaries = MatchingStudy.summarise(runs);
        assertEquals(8, summaries.size());
        summaries.forEach(s -> s.metrics().forEach((metric, ms) -> {
            assertEquals(2, ms.n(), metric);
            assertTrue(ms.sd() >= 0, metric);
        }));
        assertEquals(new MatchingStudy.MeanSd(3.0, 1.0, 3), MatchingStudy.meanSd(new double[]{2, 3, 4}), "sample sd, n - 1");
    }

    @Test
    void theSameSeedReproducesEveryNumber() {
        List<Run> second = new MatchingStudy().run(SMALL, line -> { });
        assertEquals(firstRun(), second, "runs, metrics and seeds are identical");
        assertEquals(MatchingStudyMain.checksum(MatchingStudy.summarise(firstRun())),
                MatchingStudyMain.checksum(MatchingStudy.summarise(second)));
    }

    @Test
    void theReportWritesTheTablesAndTheFiveFigures(@TempDir Path dir) throws IOException {
        List<Run> runs = firstRun();
        StudyReport.write(dir, SMALL, runs, MatchingStudy.summarise(runs));

        assertEquals(1 + 16, Files.readAllLines(dir.resolve("runs.csv")).size(), "header + one line per run");
        assertEquals(1 + 8, Files.readAllLines(dir.resolve("results.csv")).size(), "header + one line per cell");
        String md = Files.readString(dir.resolve("results.md"));
        assertTrue(md.contains("| LOW | Geo-nearest |") && md.contains("| MEDIUM | Multi-objective |"), md);
        assertTrue(md.contains(" ± "), "mean ± sd");
        String json = Files.readString(dir.resolve("results.json"));
        assertTrue(json.contains("\"baseSeed\": 99") && json.contains("\"critical_mean_wait_h\": {\"mean\":"), json);
        for (String figure : List.of("figure-1-critical-mean-wait-h.svg", "figure-2-urgent-within-6h-pct.svg",
                "figure-3-utilisation-gini.svg", "figure-4-mean-distance-km.svg", "figure-5-trade-off.svg")) {
            String svg = Files.readString(dir.resolve(figure));
            assertTrue(svg.startsWith("<svg") && svg.trim().endsWith("</svg>"), figure);
            assertTrue(svg.contains("Multi-objective") && svg.contains("standard deviation") || svg.contains("means over"), figure);
        }
    }
}
