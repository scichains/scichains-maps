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

import java.util.Arrays;
import java.util.Objects;

public abstract class ShortestPathFinder {
    public enum Algorithm {
        SIMPLE_DIJKSTRA() {
            @Override
            ShortestPathFinder build(WeightedDirectedGraph graph) {
                return new SimpleDijkstra(graph);
            }
        },
        FOR_SORTED_ACYCLIC() {
            @Override
            ShortestPathFinder build(WeightedDirectedGraph graph) {
                return new ForSortedAcyclic(graph);
            }
        };

        abstract ShortestPathFinder build(WeightedDirectedGraph graph);
    }

    final WeightedDirectedGraph graph;
    final int n;
    final int[] previousInPath;
    final double[] distances;

    ShortestPathFinder(WeightedDirectedGraph graph) {
        this.graph = Objects.requireNonNull(graph, "Null graph");
        this.n = graph.numberOfVertices();
        this.previousInPath = new int[n];
        this.distances = new double[n];
        initialize();
    }

    public static ShortestPathFinder newInstance(Algorithm algorithm, WeightedDirectedGraph graph) {
        return algorithm.build(graph);
    }

    public WeightedDirectedGraph graph() {
        return graph;
    }

    public int numberOfVertices() {
        return n;
    }

    public abstract void findShortestPaths(int startVertex);

    public boolean pathExists(int targetVertex) {
        return previousInPath[targetVertex] >= 0;
    }

    public int getPreviousInPath(int vertex) {
        checkPathExists(vertex);
        return previousInPath[vertex];
    }

    public double getDistance(int vertex) {
        checkPathExists(vertex);
        assert distances[vertex] != Double.POSITIVE_INFINITY;
        return distances[vertex];
    }

    public int getPath(int[] result, int targetVertex) {
        Objects.requireNonNull(result, "Null result");
        checkPathExists(targetVertex);
        int length = 1;
        int vertex = targetVertex;
        int previous;
        while ((previous = previousInPath[vertex]) != vertex) {
            vertex = previous;
            length++;
            if (length > n) {
                throw new IllegalStateException("Object damaged (probably due multithreading calls)");
            }
        }
        vertex = targetVertex;
        for (int k = length - 1; k >= 0; k--) {
            result[k] = vertex;
            vertex = previousInPath[vertex];
        }
        return length;
    }

    void initialize() {
        Arrays.fill(previousInPath, 0, n, -1);
        // - HIGH_BIT is set
        Arrays.fill(distances, 0, n, Double.POSITIVE_INFINITY);
    }

    private void checkPathExists(int vertex) {
        if (previousInPath[vertex] < 0) {
            throw new IllegalStateException("Path to the given vertex #" + vertex
                    + " does not exist or not calculated yet");
        }
    }

    static class SimpleDijkstra extends ShortestPathFinder {
        private final boolean[] ready;

        SimpleDijkstra(WeightedDirectedGraph graph) {
            super(graph);
            this.ready = new boolean[n];
        }

        @Override
        public void findShortestPaths(int startVertex) {
            graph.checkVertexIndex(startVertex);
            initialize();
            Arrays.fill(ready, false);
            previousInPath[startVertex] = startVertex;
            distances[startVertex] = 0.0;
            for (;;) {
                int minimalVertex = -1;
                double minimalDistance = Double.POSITIVE_INFINITY;
                for (int v = 0; v < n; v++) {
                    if (!ready[v] && distances[v] < minimalDistance) {
                        minimalVertex = v;
                        minimalDistance = distances[v];
                    }
                }
                if (minimalVertex == -1) {
                    // - eigher all vertices already visited (HIGH_BIT cleared),
                    // or all non-visited have infinite distance (other connected components)
                    break;
                }
                assert minimalDistance != Double.POSITIVE_INFINITY;
                for (int i = 0, m = graph.numberOfOutgoingEdges(minimalVertex); i < m; i++) {
                    final int v = graph.neighbourVertex(minimalVertex, i);
                    final double newDistance = minimalDistance + graph.edgeWeight(minimalVertex, i);
                    if (newDistance < distances[v]) {
                        assert !ready[v] : "Internal error: ready vertex #" + v + " cannot be relaxed!";
                        distances[v] = newDistance;
                        previousInPath[v] = minimalVertex;
                    }
                }
                ready[minimalVertex] = true;
            }
        }
    }

    static class ForSortedAcyclic extends ShortestPathFinder {
        ForSortedAcyclic(WeightedDirectedGraph graph) {
            super(graph);
            for (int v1 = 0; v1 < n; v1++) {
                for (int i = 0, m = graph.numberOfOutgoingEdges(v1); i < m; i++) {
                    final int v2 = graph.neighbourVertex(v1, i);
                    if (v2 <= v1) {
                        throw new IllegalArgumentException("It  is not an acyclic topologically-sorted graph:"
                            + "vertex " + v1 + " has an outgoing edge to " + v2 + " <= " + v1);
                    }
                }
            }
        }

        @Override
        public void findShortestPaths(int startVertex) {
            graph.checkVertexIndex(startVertex);
            initialize();
            previousInPath[startVertex] = startVertex;
            distances[startVertex] = 0.0;
            for (int v1 = startVertex; v1 < n; v1++) {
                // - note: there cannot be paths to vertices BEFORE startVertex in sorted acyclic graph
                final double distance = distances[v1];
                for (int i = 0, m = graph.numberOfOutgoingEdges(v1); i < m; i++) {
                    final int v2 = graph.neighbourVertex(v1, i);
                    final double newDistance = distance + graph.edgeWeight(v1, i);
                    if (newDistance < distances[v2]) {
                        distances[v2] = newDistance;
                        previousInPath[v2] = v1;
                    }
                }
            }
        }
    }
}
