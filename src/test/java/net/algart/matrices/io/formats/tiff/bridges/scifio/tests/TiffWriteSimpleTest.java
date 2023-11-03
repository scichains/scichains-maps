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

package net.algart.matrices.io.formats.tiff.bridges.scifio.tests;

import io.scif.FormatException;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffWriter;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffMap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class TiffWriteSimpleTest {
    private final static int IMAGE_WIDTH = 100;
    private final static int IMAGE_HEIGHT = 100;

    public static void main(String[] args) throws IOException, FormatException {
        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("    " + TiffWriteSimpleTest.class.getName() +
                    "target.tiff");
            return;
        }
        final Path targetFile = Paths.get(args[0]);

        System.out.println("Writing TIFF " + targetFile);
        try (final TiffWriter writer = new TiffWriter(targetFile)) {
            writer.setByteFiller((byte) 0xB0);
            writer.startNewFile();
            // writer.startNewFile(); // - not a problem to call twice
            TiffIFD ifd = new TiffIFD();
            ifd.putImageDimensions(IMAGE_WIDTH, IMAGE_HEIGHT);
//            ifd.put(DetailedIFD.BITS_PER_SAMPLE, new int[] {5, 5, 5});
//            ifd.put(DetailedIFD.SAMPLE_FORMAT, DetailedIFD.SAMPLE_FORMAT_IEEEFP);
            TiffMap map = writer.newMap(ifd);
            // map = writer.newMap(ifd); - will throw an exception
            System.out.printf("Saved IFD:%n%s%n", ifd.toString(TiffIFD.StringFormat.NORMAL));

            final byte[] samples = new byte[IMAGE_WIDTH * IMAGE_HEIGHT];
            Arrays.fill(samples, (byte) 40);
            writer.updateImage(map, samples);
            // writer.writeForward(map); // - uncomment to write IFD BEFORE image
            writer.complete(map);
            // writer.writeSamples(map, samples); // - equivalent to previous 3 methods
        }
        System.out.println("Done");
    }
}
