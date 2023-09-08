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
import io.scif.formats.tiff.TiffCompression;
import net.algart.matrices.io.formats.tiff.bridges.scifio.DetailedIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffWriter;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffMap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;

public class TiffWriteHugeFileTest {
    private final static int IMAGE_WIDTH = 10 * 1024;
    private final static int IMAGE_HEIGHT = 10 * 1024;

    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        boolean bigTiff = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-bigTiff")) {
            bigTiff = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.println("    " + TiffWriteHugeFileTest.class.getName() +
                    " [-bigTiff] " +
                    "target.tiff number_of_images");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex++]);
        final int numberOfImages = Integer.parseInt(args[startArgIndex++]);

        try (final TiffWriter writer = new TiffWriter(null, targetFile)) {
            writer.setBigTiff(bigTiff);
            writer.setLittleEndian(true);
            writer.startNewFile();
            for (int k = 1; k <= numberOfImages; k++) {
                DetailedIFD ifd = new DetailedIFD().putImageDimensions(IMAGE_WIDTH, IMAGE_HEIGHT);
                ifd.putTileSizes(1024, 1024);
                ifd.putCompression(TiffCompression.UNCOMPRESSED);
                final TiffMap map = writer.startNewImage(ifd, 3, byte.class);

                final byte[] samples = new byte[IMAGE_WIDTH * IMAGE_HEIGHT * 3];
                Arrays.fill(samples, (byte) (10 * k));
                long t1 = System.nanoTime();

                writer.writeImage(map, samples);
                long t2 = System.nanoTime();
                System.out.printf(Locale.US, "Image #%d/%d: %dx%d written in %.3f ms, %.3f MB/sec%n",
                        k, numberOfImages, IMAGE_WIDTH, IMAGE_HEIGHT,
                        (t2 - t1) * 1e-6, samples.length / 1048576.0 / ((t2 - t1) * 1e-9));

            }
        }

        System.out.printf("%nDone%n");
    }
}
