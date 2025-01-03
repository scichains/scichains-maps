/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2025 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.executors.modules.cv.matrices.maps.frames.graph;

import net.algart.executors.modules.maps.frames.graph.ShortestPathFinder;
import net.algart.executors.modules.maps.frames.graph.SimpleWeightedDirectedGraph;
import net.algart.executors.modules.maps.frames.graph.WeightedDirectedGraph;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class ShortestPathTest {
    private static void print(ShortestPathFinder finder, Path file) throws IOException {
        int[] shortestPath = new int[finder.numberOfVertices()];
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            for (int k = 0; k < finder.numberOfVertices(); k++) {
                if (finder.pathExists(k)) {
                    final int length = finder.getPath(shortestPath, k);
                    writer.write(String.format(Locale.US, "%d: %f, %d vertices %s%n",
                            k,
                            finder.getDistance(k),
                            length,
                            Arrays.toString(Arrays.copyOf(shortestPath, length))));
                } else {
                    writer.write(String.format("%d: no path%n", k));
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        final boolean reverse;
        if (args.length > startArgIndex && args[startArgIndex].equals("-reverse")) {
            reverse = true;
            startArgIndex++;
        } else {
            reverse = false;
        }
        if (args.length < startArgIndex + 4) {
            System.out.printf("Usage: %s [-reverse] startVertex sourceFile.txt resultFile1.txt resultFile2.txt%n",
                    ShortestPathTest.class.getName());
            return;
        }
        final int startVertex = Integer.parseInt(args[startArgIndex]);
        final Path sourceFile = Paths.get(args[startArgIndex + 1]);
        final Path resultFile1 = Paths.get(args[startArgIndex + 2]);
        final Path resultFile2 = Paths.get(args[startArgIndex + 3]);

        final List<SimpleWeightedDirectedGraph.Edge> edges = new ArrayList<>();
        try (Stream<String> stream = Files.lines(sourceFile)) {
            stream.forEach(s -> {
                String[] split = s.split("[\\s,]+");
                SimpleWeightedDirectedGraph.Edge edge = new SimpleWeightedDirectedGraph.Edge(
                        Integer.parseInt(split[0]),
                        Integer.parseInt(split[1]),
                        Double.parseDouble(split[2]));
                edges.add(edge);
                if (reverse) {
                    edges.add(edge.reverse());
                }
            });
        }
        final WeightedDirectedGraph graph = new SimpleWeightedDirectedGraph(edges);
        ShortestPathFinder finder = ShortestPathFinder.newInstance(
                ShortestPathFinder.Algorithm.SIMPLE_DIJKSTRA,
                graph);
        finder.findShortestPaths(startVertex);
        print(finder, resultFile1);

        finder = ShortestPathFinder.newInstance(
                ShortestPathFinder.Algorithm.FOR_SORTED_ACYCLIC,
                graph);
        finder.findShortestPaths(startVertex);
        print(finder, resultFile2);
    }
}
