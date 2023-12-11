/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2023 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import java.util.Objects;

public final class SimpleWeightedDirectedGraph implements WeightedDirectedGraph {
    public static class Edge {
         final int vertexFrom;
         final int vertexTo;
         final double weight;

        public Edge(int vertexFrom, int vertexTo, double weight) {
            if (vertexFrom < 0) {
                throw new IllegalArgumentException("Negative vertexFrom");
            }
            if (vertexTo < 0) {
                throw new IllegalArgumentException("Negative vertexTo");
            }
            this.vertexFrom = vertexFrom;
            this.vertexTo = vertexTo;
            this.weight = weight;
        }

        public int vertexFrom() {
            return vertexFrom;
        }

        public int vertexTo() {
            return vertexTo;
        }

        public double weight() {
            return weight;
        }

        public Edge reverse() {
            return new Edge(vertexTo, vertexFrom, weight);
        }
    }

    private final int numberOfVertices;
    private final int[] numberOfOutgoingEdges;
    private final int[][] neighboursVertices;
    private final double[][] edgeWeights;

    public SimpleWeightedDirectedGraph(Iterable<Edge> edges) {
        this(maxVertexIndex(edges) + 1, edges);
    }

    public SimpleWeightedDirectedGraph(int numberOfVertices, Iterable<Edge> edges) {
        Objects.requireNonNull(edges, "Null edges");
        if (numberOfVertices < 0) {
            throw new IllegalArgumentException("Negative numberOfVertices");
        }
        this.numberOfVertices = numberOfVertices;
        this.numberOfOutgoingEdges = new int[numberOfVertices];
        // - zero-filled by Java
        for (Edge edge : edges) {
            checkVertexIndex(edge.vertexFrom);
            checkVertexIndex(edge.vertexTo);
            this.numberOfOutgoingEdges[edge.vertexFrom]++;
        }
        this.neighboursVertices = new int[numberOfVertices][];
        this.edgeWeights = new double[numberOfVertices][];
        for (int k = 0; k < numberOfVertices; k++) {
            this.neighboursVertices[k] = new int[numberOfOutgoingEdges(k)];
            this.edgeWeights[k] = new double[numberOfOutgoingEdges(k)];
        }
        int[] indexes = new int[numberOfVertices];
        // - zero-filled by Java
        for (Edge edge : edges) {
            final int v = edge.vertexFrom;
            this.neighboursVertices[v][indexes[v]] = edge.vertexTo;
            this.edgeWeights[v][indexes[v]] = edge.weight;
            indexes[v]++;
        }
    }

    @Override
    public int numberOfVertices() {
        return numberOfVertices;
    }

    @Override
    public int numberOfOutgoingEdges(int vertex) {
        return numberOfOutgoingEdges[vertex];
    }

    @Override
    public int neighbourVertex(int vertex, int neighbourIndex) {
        return neighboursVertices[vertex][neighbourIndex];
    }

    @Override
    public double edgeWeight(int vertex, int neighbourIndex) {
        return edgeWeights[vertex][neighbourIndex];
    }

    private static int maxVertexIndex(Iterable<Edge> edges) {
        Objects.requireNonNull(edges, "Null edges");
        int result = -1;
        for (Edge edge : edges) {
            result = Math.max(result, edge.vertexFrom);
            result = Math.max(result, edge.vertexTo);
        }
        return result;
    }
}
