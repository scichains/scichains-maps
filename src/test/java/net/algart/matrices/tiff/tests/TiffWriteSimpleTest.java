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

package net.algart.matrices.tiff.tests;

import io.scif.FormatException;
import io.scif.formats.tiff.PhotoInterp;
import io.scif.formats.tiff.TiffCompression;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tiles.TiffMap;

import java.io.IOException;
import java.lang.reflect.Array;
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
            final int[] bitsPerSample = {8, 8, 8};
            ifd.putImageDimensions(IMAGE_WIDTH, IMAGE_HEIGHT);
            ifd.putNumberOfChannels(bitsPerSample.length);
            ifd.putCompression(TiffCompression.LZW);
            ifd.putPhotometricInterpretation(PhotoInterp.RGB);
            ifd.put(TiffIFD.BITS_PER_SAMPLE, bitsPerSample);
            ifd.put(TiffIFD.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_INT);
            // - you can comment or change the options above for thorough testing

            System.out.printf("Desired IFD:%n%s%n%n", ifd.toString(TiffIFD.StringFormat.NORMAL));

            writer.correctIFDForWriting(ifd, false);
            TiffMap map = writer.newMap(ifd);
            // map = writer.newMap(ifd); - will throw an exception
            System.out.printf("Saved IFD:%n%s%n%n", ifd.toString(TiffIFD.StringFormat.NORMAL));

            final Object samples = Array.newInstance(map.elementType(),
                    IMAGE_WIDTH * IMAGE_HEIGHT * bitsPerSample.length);
            if (samples instanceof byte[] bytes) {
                Arrays.fill(bytes, (byte) 70);
            }
            writer.updateImage(map, samples);
            // writer.writeForward(map); // - uncomment to write IFD BEFORE image
            writer.complete(map);
            // writer.writeSamples(map, samples); // - equivalent to previous 3 methods
        }
        System.out.println("Done");
    }
}