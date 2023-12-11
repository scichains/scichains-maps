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

package net.algart.executors.modules.cv.matrices.maps.frames.joints;

import net.algart.contours.Contours;
import net.algart.contours.ContourHeader;
import net.algart.contours.ContourJoiner;
import net.algart.arrays.Arrays;
import net.algart.arrays.MutableIntArray;


public final class SimpleContourJoinerTest {
    private static void printContours(Contours contours) {
        System.out.println(contours);
        for (int k = 0; k < contours.numberOfContours(); k++) {
            final MutableIntArray contour = contours.unpackContour(k);
            System.out.printf("Contour #%d: %d points%n    %s%n",
                    k,
                    Contours.getContourNumberOfPoints(contour),
                    Arrays.toString(contour, ", ", 256));
        }
    }

    public static void main(String[] args) {
        Contours contours = Contours.newInstance();
        contours.addContour(new ContourHeader(0), new int[] {0, 0, 5, 0, 5, 5, 0, 5});
        contours.addContour(new ContourHeader(1), new int[] {5, 1, 5, 0, 6, 0, 6, 1});
        contours.addContour(new ContourHeader(2), new int[] {-1, 5, 2, 5, 2, 6, -1, 6});
        ContourJoiner joiner = ContourJoiner.newInstance(contours, null, new int[] {10, 10, 10});
        final Contours result = joiner.joinContours();
        System.out.print("Before joining: ");
        printContours(contours);
        System.out.println();
        System.out.print("After joining: ");
        printContours(result);
    }
}
