/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2024 Daniel Alievsky, AlgART Laboratory (http://algart.net)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.algart.executors.modules.maps.frames.graph;

import net.algart.arrays.TooLargeArrayException;

import java.util.Objects;

/**
 * See <a href="https://archive.siam.org/journals/plagiary/LinkQuadratic.pdf">
 * <b>A Faster Algorithm for Computing the Link Distance Between Two Point Sets on the RealLine</b>.</a><br>
 * Justin Colannino, Godfried Toussain
 */
public final class MinimalCostLinkingOnStraight implements WeightedDirectedGraph {
    private final double[] source;
    private final double[] target;
    private final int numberOfVertices;
    private final int[] sIndex;
    private final int[] tIndex;
    private final int[] neighbourOffset;
    private final ShortestPathFinder finder;

    private final int[] resultShortestPath;
    private int numberOfLinks = 0;

    private MinimalCostLinkingOnStraight(ShortestPathFinder.Algorithm algorithm, double[] source, double[] target) {
        Objects.requireNonNull(algorithm, "Null algorithm");
        Objects.requireNonNull(source, "Null source");
        Objects.requireNonNull(target, "Null target");
        if ((long) source.length * (long) target.length + 1 > Integer.MAX_VALUE) {
            throw new TooLargeArrayException("Too large array: source.length * target.length + 1 = "
                    + source.length + " * " + target.length + " + 1 > Integer.MAX_VALUE");
        }
        checkSorted(source);
        checkSorted(target);
        this.source = source;
        this.target = target;
        this.numberOfVertices = source.length * target.length + 1;
        this.sIndex = new int[this.numberOfVertices];
        this.tIndex = new int[this.numberOfVertices];
        for (int v = 1; v < this.numberOfVertices; v++) {
            this.sIndex[v] = (v - 1) % this.source.length;
            this.tIndex[v] = (v - 1) / this.source.length;
        }
        this.neighbourOffset = new int[]{
                1,
                source.length,
                source.length + 1
        };
        this.finder = ShortestPathFinder.newInstance(algorithm, this);
        this.resultShortestPath = new int[source.length + target.length + 1];
    }

    public static MinimalCostLinkingOnStraight newInstance(
            ShortestPathFinder.Algorithm algorithm,
            double[] source,
            double[] target) {
        return new MinimalCostLinkingOnStraight(algorithm, source, target);
    }

    public double[] source() {
        return source;
    }

    public double[] target() {
        return target;
    }

    public void findBestLinks() {
        finder.findShortestPaths(0);
        final int targetVertex = numberOfVertices - 1;
        this.numberOfLinks = finder.getPath(resultShortestPath, targetVertex) - 1;
    }

    public int getNumberOfLinks() {
        return numberOfLinks;
    }

    public int getSourceIndex(int linkIndex) {
        checkIndex(linkIndex);
        return sIndex[resultShortestPath[linkIndex + 1]];
    }

    public int getTargetIndex(int linkIndex) {
        checkIndex(linkIndex);
        return tIndex[resultShortestPath[linkIndex + 1]];
    }

    public double getLinkCost(int linkIndex) {
        final int sIndex = getSourceIndex(linkIndex);
        final int tIndex = getTargetIndex(linkIndex);
        return Math.abs(source[sIndex] - target[tIndex]);
    }

    public double getSummaryCost() {
        double sum = 0.0;
        for (int k = 0; k < numberOfLinks; k++) {
            sum += getLinkCost(k);
        }
        return sum;
    }

    @Override
    public int numberOfVertices() {
        return numberOfVertices;
    }

    @Override
    public int numberOfOutgoingEdges(int vertex) {
        if (vertex == 0) {
            return numberOfVertices > 1 ? 1 : 0;
            // - possible degenerated case, when source or target array is empty
        } else {
            if (sIndex[vertex] < source.length - 1) {
                return tIndex[vertex] < target.length - 1 ? 3 : 1;
            } else {
                return tIndex[vertex] < target.length - 1 ? 1 : 0;
            }
        }
    }

    @Override
    public int neighbourVertex(int vertex, int neighbourIndex) {
        if (vertex == 0) {
            if (numberOfVertices <= 1) {
                throw new IndexOutOfBoundsException("Degenerated graph: no edges");
            }
            return 1;
        } else {
            if (sIndex[vertex] < source.length - 1) {
                if (tIndex[vertex] < target.length - 1) {
                    return vertex + neighbourOffset[neighbourIndex];
                } else {
                    return vertex + 1;
                }
            } else {
                if (tIndex[vertex] < target.length - 1) {
                    return vertex + source.length;
                } else {
                    throw new IndexOutOfBoundsException("No neighbours for last points");
                }
            }
        }
    }

    @Override
    public double edgeWeight(int vertex, int neighbourIndex) {
        if (vertex == 0) {
            return Math.abs(source[0] - target[0]);
            // - note: IndexOutOfBoundsException when one of arrays is empty;
            // it is absolutely correct behaviour: there are no edges
        } else {
            final int sIndex = this.sIndex[vertex];
            final int tIndex = this.tIndex[vertex];
            if (sIndex < source.length - 1) {
                if (tIndex < target.length - 1) {
                    switch (neighbourIndex) {
                        case 0:
                            return Math.abs(source[sIndex + 1] - target[tIndex]);
                        case 1:
                            return Math.abs(source[sIndex] - target[tIndex + 1]);
                        case 2:
                            return Math.abs(source[sIndex + 1] - target[tIndex + 1]);
                        default:
                            throw new IndexOutOfBoundsException("neighbourIndex = " + neighbourIndex + " > 2");
                    }
                } else {
                    return Math.abs(source[sIndex + 1] - target[tIndex]);
                }
            } else {
                if (tIndex < target.length - 1) {
                    return Math.abs(source[sIndex] - target[tIndex + 1]);
                } else {
                    throw new IndexOutOfBoundsException("No neighbours");
                }
            }
        }
    }

    private void checkSorted(double[] points) {
        for (int k = 1; k < points.length; k++) {
            if (points[k] < points[k - 1]) {
                throw new IllegalArgumentException("Array of points is not sorted: p[" + k + "] = "
                        + points[k] + " < p[" + (k -1) + "] = " + points[k - 1]);
            }
        }
    }

    private void checkIndex(int k) {
        if (numberOfLinks == 0) {
            throw new IllegalStateException("Links are not found");
        }
        if (k < 0 || k >= numberOfLinks) {
            throw new IndexOutOfBoundsException("Index of link " + k + " is out of range 0.." + (numberOfLinks - 1));
        }
    }
}
