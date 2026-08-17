package com.adaworldapi.lancegraph.lab;

/**
 * Where does flattening stop?
 *
 * <p>This experiment exists because the first run of {@link ThesisExperiment} produced a result
 * that looked like a bug: the Valhalla {@code Row} array reported {@code NOT-FLAT} and cost
 * <em>more</em> per row than the stable record. Rather than explain it away, the question was
 * turned into a measurement — sweep payload sizes and find the cliff.
 *
 * <p>The answer on this build is a hard cutoff, and it lands exactly between the two categories
 * the thesis distinguishes. A wrapper around one {@code int} or one {@code long} is flattened; an
 * entity with an id plus two fields is not, in any array flavour. The thesis' central claim and
 * the VM's current flattening budget happen to draw the same line — which is a much stronger
 * result than "objects are slow", because it says <em>why</em> the line is where it is.
 *
 * <p>Each row of output is one payload shape. The types are declared in the vocabulary file so the
 * stable and Valhalla trees declare identical shapes.
 */
final class FlatteningCliffExperiment {

    private FlatteningCliffExperiment() {}

    static void run() {
        Lab.section("FLATTENING CLIFF — which payload shapes does the VM flatten?");
        Lab.kv("platform", Platform.NAME);
        Lab.kv("note", "payload = declared field bytes, ignoring any header");

        probe("LaneId", "1 int", 4, LaneId.class, LaneId.of(0));
        probe("Ordinal", "1 int", 4, Ordinal.class, Ordinal.of(0));
        probe("MaskId", "1 long", 8, MaskId.class, new MaskId(0));
        probe("RowRange", "2 long", 16, RowRange.class, RowRange.of(0));
        probe("Row", "1 long + 2 int", 16, Row.class, new Row(0, 0, 0));
    }

    private static void probe(String name, String shape, int payloadBytes, Class<?> type,
                              Object init) {
        String flat;
        try {
            flat = Platform.arrayFlatness(Platform.newArrayOf(type, 64, init));
        } catch (Throwable t) {
            flat = "REFUSED: " + t.getClass().getSimpleName() + " " + t.getMessage();
        }
        System.out.printf("%-10s %-16s payload=%2d B   array=%s%n", name, shape, payloadBytes, flat);
    }
}
