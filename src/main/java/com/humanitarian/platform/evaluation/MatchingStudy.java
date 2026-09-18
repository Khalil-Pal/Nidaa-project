package com.humanitarian.platform.evaluation;

import com.humanitarian.platform.service.PriorityWeights;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The experimental design of EV-1 and its execution: strategy (4) × load (3) ×
 * provider density (2), each cell repeated over seeded datasets. The same
 * dataset (same seed) is given to all four strategies, so the strategies are
 * compared on identical demand — a paired design. Results are means and
 * standard deviations over the repetitions; single runs are never reported.
 */
public final class MatchingStudy {

    /** Arrival rate over the 72-hour horizon. */
    public enum Load {
        LOW(1.0), MEDIUM(2.0), HIGH(4.0);

        public final double arrivalsPerHour;

        Load(double arrivalsPerHour) {
            this.arrivalsPerHour = arrivalsPerHour;
        }
    }

    /** Volunteers per settlement (three settlements). */
    public enum Density {
        SPARSE(2), DENSE(6);

        public final int providersPerCluster;

        Density(int providersPerCluster) {
            this.providersPerCluster = providersPerCluster;
        }
    }

    /**
     * A named set of priority weights. The main study runs {@link #PRODUCTION};
     * the sensitivity analysis re-runs the same datasets under the others to ask
     * whether the conclusions depend on numbers that are reasoned rather than
     * measured (`docs/SCORING.md` §5).
     */
    public record WeightVariant(String name, String what, PriorityWeights weights) {
        public static final WeightVariant PRODUCTION =
                new WeightVariant("production", "the weights the platform runs", PriorityWeights.DEFAULT);

        public static final List<WeightVariant> SENSITIVITY = List.of(
                PRODUCTION,
                new WeightVariant("levelled-vulnerability", "children, elderly and disability all +10",
                        PriorityWeights.DEFAULT.withLevelledVulnerability()),
                new WeightVariant("doubled-urgency", "urgency 80/60/40/20, everything else unchanged",
                        PriorityWeights.DEFAULT.withDoubledUrgency()),
                new WeightVariant("uncapped-waiting", "ageing never stops, so waiting can overtake urgency",
                        PriorityWeights.DEFAULT.withUncappedWaiting()),
                new WeightVariant("no-vulnerability", "children, elderly and disability count for nothing",
                        PriorityWeights.DEFAULT.withoutVulnerability()));
    }

    public record Design(List<Load> loads, List<Density> densities, int repetitions, long baseSeed) {
        public static Design full(int repetitions, long baseSeed) {
            return new Design(List.of(Load.values()), List.of(Density.values()), repetitions, baseSeed);
        }

        /** The seed of one dataset: one per (load, density, repetition), shared by the four strategies. */
        public long seed(Load load, Density density, int repetition) {
            return baseSeed + 1000L * load.ordinal() + 100L * density.ordinal() + repetition;
        }
    }

    public record Run(String strategy, Load load, Density density, int repetition, long seed, RunMetrics metrics) {
    }

    public record Cell(String strategy, Load load, Density density) {
    }

    /** One cell's mean for one metric under one weight variant, for the sensitivity table. */
    public record VariantResult(String variant, String strategy, Load load, Density density,
                                Map<String, MeanSd> metrics) {
    }

    public record MeanSd(double mean, double sd, int n) {
    }

    /** One cell's summary: per metric, mean and sample standard deviation over the repetitions. */
    public record Summary(Cell cell, Map<String, MeanSd> metrics) {
    }

    private final SyntheticDataGenerator generator = new SyntheticDataGenerator();
    private final MatchingSimulation simulation;

    public MatchingStudy(MatchingSimulation simulation) {
        this.simulation = simulation;
    }

    public MatchingStudy() {
        this(new MatchingSimulation());
    }

    /**
     * Re-runs the design under each weight variant on the same seeded datasets, so
     * the difference between variants is the weights and nothing else. Only the
     * cells that can separate strategies are used (sparse provision, where a queue
     * forms), which keeps the analysis to the conditions where the priority model
     * has anything to order.
     */
    public List<VariantResult> sensitivity(Design design, List<WeightVariant> variants, Consumer<String> progress) {
        List<VariantResult> results = new ArrayList<>();
        for (WeightVariant variant : variants) {
            Map<Cell, List<RunMetrics>> byCell = new LinkedHashMap<>();
            for (Load load : design.loads()) {
                for (Density density : design.densities()) {
                    for (int rep = 0; rep < design.repetitions(); rep++) {
                        long seed = design.seed(load, density, rep);
                        SyntheticDataset dataset = generator.generate(
                                DatasetSpec.study(seed, load.arrivalsPerHour, density.providersPerCluster));
                        for (String strategy : StudyStrategies.NAMES) {
                            byCell.computeIfAbsent(new Cell(strategy, load, density), c -> new ArrayList<>())
                                    .add(simulation.run(dataset, strategy, variant.weights()));
                        }
                    }
                    progress.accept(variant.name() + ": " + load + "/" + density + " done");
                }
            }
            byCell.forEach((cell, runs) -> {
                Map<String, MeanSd> metrics = new LinkedHashMap<>();
                for (String metric : runs.get(0).values().keySet()) {
                    metrics.put(metric, meanSd(runs.stream().mapToDouble(m -> m.values().get(metric)).toArray()));
                }
                results.add(new VariantResult(variant.name(), cell.strategy(), cell.load(), cell.density(), metrics));
            });
        }
        return results;
    }

    public List<Run> run(Design design, Consumer<String> progress) {
        List<Run> runs = new ArrayList<>();
        for (Load load : design.loads()) {
            for (Density density : design.densities()) {
                for (int rep = 0; rep < design.repetitions(); rep++) {
                    long seed = design.seed(load, density, rep);
                    SyntheticDataset dataset = generator.generate(
                            DatasetSpec.study(seed, load.arrivalsPerHour, density.providersPerCluster));
                    for (String strategy : StudyStrategies.NAMES) {
                        RunMetrics metrics = simulation.run(dataset, strategy);
                        runs.add(new Run(strategy, load, density, rep, seed, metrics));
                    }
                    progress.accept(load + "/" + density + " repetition " + (rep + 1) + "/" + design.repetitions()
                            + " (seed " + seed + ", " + dataset.requests().size() + " requests, "
                            + dataset.providers().size() + " providers)");
                }
            }
        }
        return runs;
    }

    public static List<Summary> summarise(List<Run> runs) {
        Map<Cell, List<Run>> byCell = new LinkedHashMap<>();
        for (String strategy : StudyStrategies.NAMES) {
            for (Load load : Load.values()) {
                for (Density density : Density.values()) {
                    Cell cell = new Cell(strategy, load, density);
                    List<Run> cellRuns = runs.stream()
                            .filter(r -> r.strategy().equals(strategy) && r.load() == load && r.density() == density)
                            .toList();
                    if (!cellRuns.isEmpty()) {
                        byCell.put(cell, cellRuns);
                    }
                }
            }
        }
        List<Summary> summaries = new ArrayList<>();
        byCell.forEach((cell, cellRuns) -> {
            Map<String, MeanSd> metrics = new LinkedHashMap<>();
            for (String metric : cellRuns.get(0).metrics().values().keySet()) {
                double[] values = cellRuns.stream().mapToDouble(r -> r.metrics().values().get(metric)).toArray();
                metrics.put(metric, meanSd(values));
            }
            summaries.add(new Summary(cell, metrics));
        });
        return summaries;
    }

    static MeanSd meanSd(double[] values) {
        int n = values.length;
        double mean = 0;
        for (double v : values) {
            mean += v;
        }
        mean /= n;
        double ss = 0;
        for (double v : values) {
            ss += (v - mean) * (v - mean);
        }
        double sd = n > 1 ? Math.sqrt(ss / (n - 1)) : 0;
        return new MeanSd(mean, sd, n);
    }
}
