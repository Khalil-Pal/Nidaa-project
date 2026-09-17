package com.humanitarian.platform.evaluation;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Two chart shapes the study report needs, written as plain SVG so the figures
 * are reproducible from the same run without a plotting library: grouped bars
 * with standard-deviation whiskers (one panel per density, one group per load,
 * one bar per strategy) and a trade-off scatter. Sizes are fixed; the text is
 * the figure's own legend, so the files stand alone in a thesis appendix.
 */
final class SvgCharts {

    static final Map<String, String> COLOURS = Map.of(
            "FIFO", "#94a3b8",
            "WEIGHTED_SCORING", "#2563eb",
            "GEO_NEAREST", "#16a34a",
            "MULTI_OBJECTIVE_OPTIMIZATION", "#d97706");

    static final Map<String, String> SHORT = Map.of(
            "FIFO", "FIFO",
            "WEIGHTED_SCORING", "Weighted",
            "GEO_NEAREST", "Geo-nearest",
            "MULTI_OBJECTIVE_OPTIMIZATION", "Multi-objective");

    private SvgCharts() {
    }

    /** A bar: its mean, its standard deviation. */
    record Bar(String strategy, double mean, double sd) {
    }

    /** A group of bars under one label (a load level). */
    record Group(String label, List<Bar> bars) {
    }

    /** A panel of groups under one title (a density level). */
    record Panel(String title, List<Group> groups) {
    }

    static String groupedBars(String title, String yLabel, List<Panel> panels, boolean percent) {
        int width = 960;
        int height = 400;
        int marginLeft = 64;
        int marginRight = 16;
        int marginTop = 56;
        int marginBottom = 84;
        int panelGap = 32;
        int panelWidth = (width - marginLeft - marginRight - panelGap * (panels.size() - 1)) / panels.size();
        int plotHeight = height - marginTop - marginBottom;

        StringBuilder svg = new StringBuilder();
        svg.append(header(width, height));
        svg.append(text(width / 2.0, 24, title, 16, "middle", "700"));
        svg.append(text(16, marginTop + plotHeight / 2.0, yLabel, 12, "middle", "400",
                " transform=\"rotate(-90 16 " + (marginTop + plotHeight / 2.0) + ")\""));

        for (int p = 0; p < panels.size(); p++) {
            Panel panel = panels.get(p);
            int x0 = marginLeft + p * (panelWidth + panelGap);
            int y0 = marginTop;
            // each panel has its own scale: the dense panel's values are an order of magnitude smaller
            double max = percent ? 100 : 0;
            for (Group group : panel.groups()) {
                for (Bar bar : group.bars()) {
                    max = Math.max(max, bar.mean() + bar.sd());
                }
            }
            double step = percent ? 20 : niceStep(max);
            double top = percent ? 100 : Math.max(step, Math.ceil(max / step) * step);
            svg.append(text(x0 + panelWidth / 2.0, y0 - 10, panel.title(), 13, "middle", "600"));
            // axes and gridlines, one line per step
            for (double value = 0; value <= top + 1e-9; value += step) {
                double y = y0 + plotHeight - plotHeight * value / top;
                svg.append(line(x0, y, x0 + panelWidth, y, "#e2e8f0", 1));
                svg.append(text(x0 - 6, y + 4, format(value), 10, "end", "400"));
            }
            svg.append(line(x0, y0, x0, y0 + plotHeight, "#334155", 1));
            svg.append(line(x0, y0 + plotHeight, x0 + panelWidth, y0 + plotHeight, "#334155", 1));

            int groups = panel.groups().size();
            double groupWidth = panelWidth / (double) groups;
            for (int g = 0; g < groups; g++) {
                Group group = panel.groups().get(g);
                int bars = group.bars().size();
                double barWidth = groupWidth * 0.8 / bars;
                double gx = x0 + g * groupWidth + groupWidth * 0.1;
                for (int b = 0; b < bars; b++) {
                    Bar bar = group.bars().get(b);
                    double x = gx + b * barWidth;
                    double h = plotHeight * Math.min(bar.mean(), top) / top;
                    double y = y0 + plotHeight - h;
                    svg.append(String.format(Locale.ROOT,
                            "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\"><title>%s: %s ± %s</title></rect>\n",
                            x + 1, y, barWidth - 2, h, COLOURS.get(bar.strategy()), SHORT.get(bar.strategy()),
                            format(bar.mean()), format(bar.sd())));
                    if (bar.sd() > 0) {
                        double yTop = y0 + plotHeight - plotHeight * Math.min(bar.mean() + bar.sd(), top) / top;
                        double yBottom = y0 + plotHeight - plotHeight * Math.max(bar.mean() - bar.sd(), 0) / top;
                        double cx = x + barWidth / 2;
                        svg.append(line(cx, yTop, cx, yBottom, "#0f172a", 1));
                        svg.append(line(cx - 3, yTop, cx + 3, yTop, "#0f172a", 1));
                        svg.append(line(cx - 3, yBottom, cx + 3, yBottom, "#0f172a", 1));
                    }
                }
                svg.append(text(gx + groupWidth * 0.4, y0 + plotHeight + 16, group.label(), 11, "middle", "400"));
            }
        }
        svg.append(legend(marginLeft, height - 40));
        svg.append(text(marginLeft, height - 12,
                "Bars: mean over the seeded repetitions; whiskers: ± one sample standard deviation. Each panel has its own scale.",
                10, "start", "400"));
        svg.append("</svg>\n");
        return svg.toString();
    }

    /** A point of the trade-off scatter. */
    record Point(String strategy, double x, double y, String label) {
    }

    static String scatter(String title, String xLabel, String yLabel, List<Point> points) {
        int width = 720;
        int height = 460;
        int marginLeft = 64;
        int marginRight = 24;
        int marginTop = 48;
        int marginBottom = 92;
        int plotWidth = width - marginLeft - marginRight;
        int plotHeight = height - marginTop - marginBottom;
        double stepX = niceStep(points.stream().mapToDouble(Point::x).max().orElse(1));
        double stepY = niceStep(points.stream().mapToDouble(Point::y).max().orElse(1));
        double maxX = Math.max(stepX, Math.ceil(points.stream().mapToDouble(Point::x).max().orElse(1) / stepX) * stepX);
        double maxY = Math.max(stepY, Math.ceil(points.stream().mapToDouble(Point::y).max().orElse(1) / stepY) * stepY);

        StringBuilder svg = new StringBuilder();
        svg.append(header(width, height));
        svg.append(text(width / 2.0, 24, title, 16, "middle", "700"));
        for (double value = 0; value <= maxY + 1e-9; value += stepY) {
            double y = marginTop + plotHeight - plotHeight * value / maxY;
            svg.append(line(marginLeft, y, marginLeft + plotWidth, y, "#e2e8f0", 1));
            svg.append(text(marginLeft - 6, y + 4, format(value), 10, "end", "400"));
        }
        for (double value = 0; value <= maxX + 1e-9; value += stepX) {
            double x = marginLeft + plotWidth * value / maxX;
            svg.append(line(x, marginTop, x, marginTop + plotHeight, "#e2e8f0", 1));
            svg.append(text(x, marginTop + plotHeight + 14, format(value), 10, "middle", "400"));
        }
        svg.append(line(marginLeft, marginTop, marginLeft, marginTop + plotHeight, "#334155", 1));
        svg.append(line(marginLeft, marginTop + plotHeight, marginLeft + plotWidth, marginTop + plotHeight, "#334155", 1));
        svg.append(text(marginLeft + plotWidth / 2.0, marginTop + plotHeight + 32, xLabel, 12, "middle", "400"));
        svg.append(text(16, marginTop + plotHeight / 2.0, yLabel, 12, "middle", "400",
                " transform=\"rotate(-90 16 " + (marginTop + plotHeight / 2.0) + ")\""));
        for (Point point : points) {
            double cx = marginLeft + plotWidth * point.x() / maxX;
            double cy = marginTop + plotHeight - plotHeight * point.y() / maxY;
            svg.append(String.format(Locale.ROOT,
                    "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"5\" fill=\"%s\" stroke=\"#0f172a\" stroke-width=\"0.5\"><title>%s %s: %s, %s</title></circle>\n",
                    cx, cy, COLOURS.get(point.strategy()), SHORT.get(point.strategy()), point.label(),
                    format(point.x()), format(point.y())));
            svg.append(text(cx + 7, cy + 3, point.label(), 9, "start", "400"));
        }
        svg.append(legend(marginLeft, height - 40));
        svg.append(text(marginLeft, height - 12,
                "One point per cell (means over the repetitions). Labels: load L/M/H, density S/D. Lower left is better.", 10, "start", "400"));
        svg.append("</svg>\n");
        return svg.toString();
    }

    private static String legend(int x, int y) {
        StringBuilder sb = new StringBuilder();
        int cursor = x;
        for (String strategy : StudyStrategies.NAMES) {
            sb.append(String.format(Locale.ROOT, "<rect x=\"%d\" y=\"%d\" width=\"12\" height=\"12\" fill=\"%s\"/>\n",
                    cursor, y - 10, COLOURS.get(strategy)));
            sb.append(text(cursor + 16, y, SHORT.get(strategy), 11, "start", "400"));
            cursor += 16 + SHORT.get(strategy).length() * 7 + 20;
        }
        return sb.toString();
    }

    private static String header(int width, int height) {
        return String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\" "
                        + "font-family=\"Inter, Arial, sans-serif\" fill=\"#0f172a\">\n"
                        + "<rect width=\"%d\" height=\"%d\" fill=\"#ffffff\"/>\n",
                width, height, width, height, width, height);
    }

    private static String text(double x, double y, String content, int size, String anchor, String weight) {
        return text(x, y, content, size, anchor, weight, "");
    }

    private static String text(double x, double y, String content, int size, String anchor, String weight, String extra) {
        return String.format(Locale.ROOT,
                "<text x=\"%.1f\" y=\"%.1f\" font-size=\"%d\" text-anchor=\"%s\" font-weight=\"%s\"%s>%s</text>\n",
                x, y, size, anchor, weight, extra, escape(content));
    }

    private static String line(double x1, double y1, double x2, double y2, String colour, double width) {
        return String.format(Locale.ROOT,
                "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%.1f\"/>\n",
                x1, y1, x2, y2, colour, width);
    }

    /** A round gridline step (1, 2, 2.5, 5 or 10 times a power of ten) giving four to eight lines up to {@code max}. */
    static double niceStep(double max) {
        if (max <= 0) {
            return 1;
        }
        double raw = max / 5;
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double normalised = raw / magnitude;
        double nice = normalised <= 1 ? 1 : normalised <= 2 ? 2 : normalised <= 2.5 ? 2.5 : normalised <= 5 ? 5 : 10;
        return nice * magnitude;
    }

    static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e6) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return Math.abs(value) < 10
                ? String.format(Locale.ROOT, "%.2f", value)
                : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
