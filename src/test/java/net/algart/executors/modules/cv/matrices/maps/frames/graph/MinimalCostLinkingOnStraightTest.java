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

package net.algart.executors.modules.cv.matrices.maps.frames.graph;

import net.algart.executors.modules.maps.frames.graph.MinimalCostLinkingOnStraight;
import net.algart.executors.modules.maps.frames.graph.ShortestPathFinder;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Random;

public final class MinimalCostLinkingOnStraightTest {
    private static double[] randomPoints(int n, double mult, Random rnd) {
        final double[] result = new double[n];
        double p = 0.0;
        for (int k = 0; k < n; k++) {
            final double gaussian = rnd.nextGaussian();
            final double delta = rnd.nextBoolean() && gaussian > 1.0 ?
                    gaussian * 30 :
                    rnd.nextInt(20) * 3.0;
            p += delta * mult;
            result[k] = p;
        }
        if (rnd.nextBoolean())  {
            final double shift = 0.4 * rnd.nextDouble() * p;
            for (int k = 0; k < n; k++) {
                result[k] += shift;
            }
        }
        return result;
    }

    private static BufferedImage draw(MinimalCostLinkingOnStraight linking) {
        final double[] s = linking.source();
        final double[] t = linking.target();
        final double maxPoint = Math.max(
                s.length > 0 ? s[s.length - 1] : 0.0,
                t.length > 0 ? t[t.length - 1] : 0.0);
        final BufferedImage image = new BufferedImage(
                (int) maxPoint + 100, 200, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        for (int k = 0, n = linking.getNumberOfLinks(); k < n; k++) {
            final int sIndex = linking.getSourceIndex(k);
            final int tIndex = linking.getTargetIndex(k);
            final int sX = 50 + (int) Math.round(s[sIndex]);
            final int tX = 50 + (int) Math.round(t[tIndex]);
            g.setColor(Color.RED);
            g.drawLine(sX, 0, sX, 49);
            g.setColor(Color.GREEN);
            g.drawLine(tX, 150, tX, 199);
            g.setColor(Color.YELLOW);
            g.drawLine(sX, 50, tX, 149);
        }
        g.dispose();
        return image;
    }

    private static void print(MinimalCostLinkingOnStraight linking) {
        final double[] s = linking.source();
        final double[] t = linking.target();
        for (int k = 0, n = linking.getNumberOfLinks(); k < n; k++) {
            final int sIndex = linking.getSourceIndex(k);
            final int tIndex = linking.getTargetIndex(k);
            System.out.printf(Locale.US, "Link %d/%d: %d [%f] <--> %d [%f], distance %f%n",
                    k, n, sIndex, s[sIndex], tIndex, t[tIndex], linking.getLinkCost(k));
        }
        System.out.printf(Locale.US, "Summary link distance %f%n%n", linking.getSummaryCost());
    }

    private static void checkEquality(MinimalCostLinkingOnStraight linking1, MinimalCostLinkingOnStraight linking2) {
        if (linking1.getSummaryCost() != linking2.getSummaryCost()) {
            throw new AssertionError("Different summary link cost: "
                    + linking1.getSummaryCost() + " and " + linking2.getSummaryCost());
        }
        final int n = linking1.getNumberOfLinks();
        if (linking2.getNumberOfLinks() != n) {
            throw new AssertionError("Different number of links: "
                    + n + " and " + linking2.getNumberOfLinks());
        }
        for (int k = 0; k < n; k++) {
            if (linking1.getSourceIndex(k) != linking2.getSourceIndex(k)) {
                throw new AssertionError("Different link #" + k + " source: "
                        + linking1.getSourceIndex(k) + " and " + linking2.getSourceIndex(k));
            }
            if (linking1.getTargetIndex(k) != linking2.getTargetIndex(k)) {
                throw new AssertionError("Different link #" + k + " target: "
                        + linking1.getTargetIndex(k) + " and " + linking2.getTargetIndex(k));
            }
            if (linking1.getLinkCost(k) != linking2.getLinkCost(k)) {
                throw new AssertionError("Different link #" + k + " distance: "
                        + linking1.getLinkCost(k) + " and " + linking2.getLinkCost(k));
            }
        }
    }


    private static String getFileExtension(String fileName) {
        int p = fileName.lastIndexOf('.');
        if (p == -1) {
            return "bmp";
        }
        return fileName.substring(p + 1);
    }

    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean testSimple = false;
        if (args.length > startArgIndex && args[startArgIndex].equals("-testSimple")) {
            testSimple = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 3) {
            System.out.printf("Usage: %s [-testSimple] n m resultFile.bmp%n",
                    MinimalCostLinkingOnStraightTest.class.getName());
            return;
        }
        Random rnd = new Random();
        final int numberOfSource = Integer.parseInt(args[startArgIndex]);
        final int numberOfTarget = Integer.parseInt(args[startArgIndex + 1]);
        final File resultFile = new File(args[startArgIndex + 2]);
        MinimalCostLinkingOnStraight simple = null, fast = null;
        for (int test = 1; test <= 32; test++) {
            final double[] source = randomPoints(numberOfSource, 1.0, rnd);
            final double[] target = randomPoints(numberOfTarget, numberOfSource / (double) numberOfTarget, rnd);
            long t1 = System.nanoTime();
            if (testSimple) {
                simple = MinimalCostLinkingOnStraight.newInstance(
                        ShortestPathFinder.Algorithm.SIMPLE_DIJKSTRA,
                        source,
                        target);
            }
            long t2 = System.nanoTime();
            if (testSimple) {
                simple.findBestLinks();
            }
            long t3 = System.nanoTime();
            fast = MinimalCostLinkingOnStraight.newInstance(
                    ShortestPathFinder.Algorithm.FOR_SORTED_ACYCLIC,
                    source,
                    target);
            long t4 = System.nanoTime();
            fast.findBestLinks();
            long t5 = System.nanoTime();
            System.out.printf(Locale.US, "Test #%d: "
                            + "simple: %.3f ms creating, %.3f ms calculating; "
                            + "fast: %.3f ms creating, %.3f ms calculating%n",
                    test, (t2 - t1) * 1e-6, (t3 - t2) * 1e-6, (t4 - t3) * 1e-6, (t5 - t4) * 1e-6);
            if (testSimple) {
                checkEquality(simple, fast);
            }
        }
        System.out.println();
        final String name = resultFile.getName();
        if (simple != null) {
            print(simple);
        }
        print(fast);
        ImageIO.write(draw(fast), getFileExtension(name), resultFile);
    }
}
