package com.adaworldapi.lancegraph;

/**
 * The result of {@link RowStore#facetSum}: a sum, and the grouping it was <em>resolved</em> under.
 *
 * <p>The second field is the point. {@link RowStore#facetSumAs} takes a grouping on trust and
 * returns a bare number, so a caller who guessed wrong gets a plausible answer and no signal.
 * Here the grouping comes back with the sum, derived from the population's own classes — so the
 * caller can assert what it got rather than assume what it asked for.
 *
 * @param sum     the sum of every group of the facet's register, over the selected rows
 * @param carving the grouping the population resolved to
 */
public record FacetSum(long sum, Carving carving) {}
