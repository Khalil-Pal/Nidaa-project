package com.humanitarian.platform.evaluation;

import com.humanitarian.platform.evaluation.MatchingStudy.Cell;
import com.humanitarian.platform.evaluation.MatchingStudy.Density;
import com.humanitarian.platform.evaluation.MatchingStudy.Design;
import com.humanitarian.platform.evaluation.MatchingStudy.Load;
import com.humanitarian.platform.evaluation.MatchingStudy.MeanSd;
import com.humanitarian.platform.evaluation.MatchingStudy.Run;
import com.humanitarian.platform.evaluation.MatchingStudy.Summary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Writes one study run to a directory: every run's metrics ({@code runs.csv}),
 * the per-cell means and standard deviations ({@code results.csv},
 * {@code results.json}, {@code results.md}) and five SVG figures. The files are
 * plain text so a re-run with the same seed can be diffed against the committed
 * ones.
 */
public final class StudyReport {

    /** The metrics charted as grouped bars, in figure order. */
    static final List<String> CHARTED = List.of(
            "critical_mean_wait_h", "urgent_within_6h_pct", "utilisation_gini", "mean_distance_km");

    /** The metrics tabulated in results.md. */
    static final List<String> TABULATED = List.of(
            "critical_mean_wait_h", "critical_p95_wait_h", "urgent_within_6h_pct", "p95_wait_h", "utilisation_gini",
            "mean_distance_km", "completed_within_horizon_pct", "regional_fairness_jain");

    private StudyReport() {
    }

    public static void write(Path dir, Design design, List<Run> runs, List<Summary> summaries) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("runs.csv"), runsCsv(runs), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("results.csv"), resultsCsv(summaries), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("results.json"), resultsJson(design, summaries), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("results.md"), resultsMarkdown(design, summaries), StandardCharsets.UTF_8);
        int figure = 1;
        for (String metric : CHARTED) {
            Files.writeString(dir.resolve("figure-" + figure + "-" + metric.replace('_', '-') + ".svg"),
                    barChart(metric, summaries), StandardCharsets.UTF_8);
            figure++;
        }
        Files.writeString(dir.resolve("figure-5-trade-off.svg"), tradeOff(summaries), StandardCharsets.UTF_8);
    }

    static String runsCsv(List<Run> runs) {
        StringBuilder sb = new StringBuilder("strategy,load,density,repetition,seed,requests,providers");
        List<String> metrics = new ArrayList<>(runs.get(0).metrics().values().keySet());
        metrics.forEach(m -> sb.append(',').append(m));
        sb.append('\n');
        for (Run run : runs) {
            sb.append(run.strategy()).append(',').append(run.load()).append(',').append(run.density()).append(',')
                    .append(run.repetition()).append(',').append(run.seed()).append(',')
                    .append(run.metrics().requestCount()).append(',').append(run.metrics().providerCount());
            Map<String, Double> values = run.metrics().values();
            metrics.forEach(m -> sb.append(',').append(number(values.get(m))));
            sb.append('\n');
        }
        return sb.toString();
    }

    static String resultsCsv(List<Summary> summaries) {
        StringBuilder sb = new StringBuilder("strategy,load,density,n");
        List<String> metrics = new ArrayList<>(summaries.get(0).metrics().keySet());
        metrics.forEach(m -> sb.append(',').append(m).append("_mean,").append(m).append("_sd"));
        sb.append('\n');
        for (Summary s : summaries) {
            sb.append(s.cell().strategy()).append(',').append(s.cell().load()).append(',')
                    .append(s.cell().density()).append(',').append(s.metrics().values().iterator().next().n());
            metrics.forEach(m -> sb.append(',').append(number(s.metrics().get(m).mean()))
                    .append(',').append(number(s.metrics().get(m).sd())));
            sb.append('\n');
        }
        return sb.toString();
    }

    static String resultsJson(Design design, List<Summary> summaries) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"design\": {\"strategies\": [");
        sb.append(String.join(", ", StudyStrategies.NAMES.stream().map(n -> "\"" + n + "\"").toList()));
        sb.append("], \"loads\": [");
        sb.append(String.join(", ", design.loads().stream()
                .map(l -> "{\"level\": \"" + l + "\", \"arrivalsPerHour\": " + number(l.arrivalsPerHour) + "}").toList()));
        sb.append("], \"densities\": [");
        sb.append(String.join(", ", design.densities().stream()
                .map(d -> "{\"level\": \"" + d + "\", \"providersPerCluster\": " + d.providersPerCluster + "}").toList()));
        sb.append("], \"repetitions\": ").append(design.repetitions())
                .append(", \"baseSeed\": ").append(design.baseSeed()).append("},\n");
        sb.append("  \"cells\": [\n");
        for (int i = 0; i < summaries.size(); i++) {
            Summary s = summaries.get(i);
            sb.append("    {\"strategy\": \"").append(s.cell().strategy()).append("\", \"load\": \"")
                    .append(s.cell().load()).append("\", \"density\": \"").append(s.cell().density()).append("\"");
            s.metrics().forEach((metric, ms) -> sb.append(", \"").append(metric).append("\": {\"mean\": ")
                    .append(number(ms.mean())).append(", \"sd\": ").append(number(ms.sd()))
                    .append(", \"n\": ").append(ms.n()).append("}"));
            sb.append("}").append(i + 1 < summaries.size() ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    static String resultsMarkdown(Design design, List<Summary> summaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("# EV-1 results table\n\n");
        sb.append("Generated by `MatchingStudyMain` (base seed ").append(design.baseSeed()).append(", ")
                .append(design.repetitions()).append(" seeded repetitions per cell). Each entry is mean ± sample ")
                .append("standard deviation over the repetitions; the same seeded datasets were given to all four ")
                .append("strategies. Loads: LOW 1, MEDIUM 2, HIGH 4 arrivals per hour over 72 hours. Densities: ")
                .append("SPARSE 2 and DENSE 6 volunteers per settlement (three settlements).\n\n");
        for (Density density : design.densities()) {
            sb.append("## ").append(density).append(" (").append(density.providersPerCluster * 3).append(" providers)\n\n");
            sb.append("| Load | Strategy |");
            TABULATED.forEach(m -> sb.append(' ').append(RunMetrics.LABELS.get(m)).append(" |"));
            sb.append("\n|---|---|");
            TABULATED.forEach(m -> sb.append("---:|"));
            sb.append('\n');
            for (Load load : design.loads()) {
                for (String strategy : StudyStrategies.NAMES) {
                    Optional<Summary> summary = find(summaries, new Cell(strategy, load, density));
                    if (summary.isEmpty()) {
                        continue;
                    }
                    sb.append("| ").append(load).append(" | ").append(SvgCharts.SHORT.get(strategy)).append(" |");
                    for (String metric : TABULATED) {
                        MeanSd ms = summary.get().metrics().get(metric);
                        sb.append(' ').append(SvgCharts.format(ms.mean())).append(" ± ")
                                .append(SvgCharts.format(ms.sd())).append(" |");
                    }
                    sb.append('\n');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String barChart(String metric, List<Summary> summaries) {
        List<SvgCharts.Panel> panels = new ArrayList<>();
        for (Density density : Density.values()) {
            List<SvgCharts.Group> groups = new ArrayList<>();
            for (Load load : Load.values()) {
                List<SvgCharts.Bar> bars = new ArrayList<>();
                for (String strategy : StudyStrategies.NAMES) {
                    find(summaries, new Cell(strategy, load, density)).ifPresent(s ->
                            bars.add(new SvgCharts.Bar(strategy, s.metrics().get(metric).mean(), s.metrics().get(metric).sd())));
                }
                if (!bars.isEmpty()) {
                    groups.add(new SvgCharts.Group(load + " load (" + SvgCharts.format(load.arrivalsPerHour) + "/h)", bars));
                }
            }
            if (!groups.isEmpty()) {
                panels.add(new SvgCharts.Panel(density + ": " + density.providersPerCluster + " providers per settlement", groups));
            }
        }
        return SvgCharts.groupedBars(RunMetrics.LABELS.get(metric), RunMetrics.LABELS.get(metric), panels,
                metric.endsWith("_pct"));
    }

    /** Sparse provision only: under dense provision CRITICAL waiting is near zero for every strategy (figure 1). */
    static String tradeOff(List<Summary> summaries) {
        List<SvgCharts.Point> points = new ArrayList<>();
        for (Summary s : summaries) {
            if (s.cell().density() != Density.SPARSE) {
                continue;
            }
            String label = s.cell().load().name().charAt(0) + "/" + s.cell().density().name().charAt(0);
            points.add(new SvgCharts.Point(s.cell().strategy(),
                    s.metrics().get("mean_distance_km").mean(),
                    s.metrics().get("critical_mean_wait_h").mean(), label));
        }
        return SvgCharts.scatter("The trade-off under sparse provision: travel distance against CRITICAL waiting time",
                "Mean travel distance (km)", "CRITICAL mean waiting time (h)", points);
    }

    private static Optional<Summary> find(List<Summary> summaries, Cell cell) {
        return summaries.stream().filter(s -> s.cell().equals(cell)).findFirst();
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
