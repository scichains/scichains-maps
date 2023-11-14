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

package net.algart.matrices.tiff.tests.misc;

import io.scif.FormatException;
import io.scif.formats.tiff.TiffCompression;
import net.algart.matrices.tiff.TiffIFD;

public class CreatingIFDTest {
    private static void showIFD(TiffIFD ifd, String name) {
        System.out.printf("%s%nBrief:%n----%n%s%n----%nNormal:%n----%n%s%n----%n%n",
                name, ifd, ifd.toString(TiffIFD.StringFormat.NORMAL_SORTED));
    }

    public static void main(String[] args) throws FormatException {
        TiffIFD ifd = new TiffIFD();
        showIFD(ifd, "Empty");

        ifd.putPixelInformation(1, byte.class);
        showIFD(ifd, "Pixel information");

        ifd.putCompression(TiffCompression.OLD_JPEG);
        // - 3 channels, not 1
        showIFD(ifd, "Compression");

        ifd.putImageDimensions(3000, 3000);
        // ifd.put(IFD.IMAGE_WIDTH, 3e20);
        // - previous operator enforces exception while showing IFD
        showIFD(ifd, "Dimensions");
    }
}