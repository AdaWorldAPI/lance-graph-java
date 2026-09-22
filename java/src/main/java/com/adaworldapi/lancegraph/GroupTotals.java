package com.adaworldapi.lancegraph;

/**
 * The answer to a grouped sum: one widened total per group, addressed by the group's key value.
 *
 * <p>This is what {@code SELECT key, SUM(value) … GROUP BY key} hands back — the same thing a
 * {@code ResultSet} would, without the cursor. {@link #total(int)} is the row for key {@code g};
 * {@link #groups()} is how many keys were asked for. A key no selected row carries has a total of
 * {@code 0}, exactly as SQL would report it with an outer join onto the key domain.
 *
 * <p>Sized by the question, never by the data: a {@code GroupTotals} over 16 keys is 16 numbers
 * whether the view spans a thousand rows or a billion. No row, no selection and no index list is
 * behind it — the totals were folded natively in one pass and only the totals crossed.
 *
 * <p>Immutable. There is no accessor for the underlying storage; the totals are read one at a
 * time by key, which is also the only way a caller ever needs them.
 */
public final class GroupTotals {

    private final long[] totals;

    GroupTotals(long[] totals) {
        this.totals = totals;
    }

    /** How many groups this answer covers — the {@code groups} the caller asked for. */
    public int groups() {
        return totals.length;
    }

    /**
     * The sum for key {@code group}, widened to 64 bits; {@code 0} when no selected row carried
     * that key.
     *
     * @throws IndexOutOfBoundsException if {@code group} is not in {@code [0, groups())}
     */
    public long total(int group) {
        java.util.Objects.checkIndex(group, totals.length);
        return totals[group];
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("GroupTotals[");
        for (int g = 0; g < totals.length; g++) {
            if (g > 0) {
                sb.append(", ");
            }
            sb.append(g).append('=').append(totals[g]);
        }
        return sb.append(']').toString();
    }
}
