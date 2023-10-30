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

package net.algart.matrices.io.formats.tiff.bridges.scifio.tests.misc;

import io.scif.FormatException;
import io.scif.formats.tiff.IFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.DetailedIFD;

import java.util.Optional;

public class IFDGetValueTest {
    static void showTag(DetailedIFD ifd, int tag, Class<?> requiredClass) {
        System.out.printf("Tag %s, required class %s:%n",
                DetailedIFD.ifdTagName(tag, true),
                requiredClass.getSimpleName());
        Optional<?> opt = ifd.optValue(tag, requiredClass);
        System.out.printf("optValue: %s [%s]%n",
                opt, opt.map(o -> o.getClass().getSimpleName()).orElse("n/a"));
        try {
            opt = ifd.getValue(tag, requiredClass, true);
            System.out.printf("optValue(..., true): %s [%s]%n",
                    opt, opt.map(o -> o.getClass().getSimpleName()).orElse("n/a"));
        } catch (FormatException e) {
            System.out.printf("getValue: %s%n", e);
        }
        try {
            Object v = ifd.reqValue(tag, requiredClass);
            System.out.printf("reqValue: %s [%s]%n", v, v.getClass().getSimpleName());
        } catch (FormatException e) {
            System.out.printf("reqValue: %s%n", e);
        }
        System.out.printf("optType: %s%n", ifd.optType(tag));
        System.out.println();
    }

    public static void main(String[] args) {
        DetailedIFD ifd = new DetailedIFD();
        ifd.putPixelInformation(3, byte.class);
        showTag(ifd, IFD.SAMPLES_PER_PIXEL, Integer.class);
        showTag(ifd, IFD.BITS_PER_SAMPLE, Integer.class);
        showTag(ifd, IFD.BITS_PER_SAMPLE, int[].class);

        ifd.putImageDimensions(3000, 3000);
        showTag(ifd, IFD.IMAGE_WIDTH, Integer.class);
        showTag(ifd, IFD.IMAGE_WIDTH, Long.class);
    }
}
