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

package net.algart.scifio.tiff.tests;

import io.scif.FormatException;
import io.scif.SCIFIO;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.PhotoInterp;
import io.scif.formats.tiff.TiffCompression;
import net.algart.scifio.tiff.experimental.SequentialTiffWriter;
import net.algart.scifio.tiff.experimental.TiffSaver;
import net.algart.scifio.tiff.improvements.ExtendedIFD;
import net.algart.scifio.tiff.improvements.TiffParser;
import org.scijava.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class SimpleWriteTiffTest {
    private final static int WIDTH = 501;
    private final static int HEIGHT = 511;

    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 1) {
            System.out.println("Usage:");
            System.out.println("    " + SimpleWriteTiffTest.class.getName()
                    + " target.tif [number_of_images [compression]]");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex]);
        final int numberOfImages = startArgIndex + 1 < args.length ? Integer.parseInt(args[startArgIndex + 1]) : 1;
        final String compression = startArgIndex + 2 < args.length ? args[startArgIndex + 2] : null;

        final SCIFIO scifio = new SCIFIO();
        try (Context context = scifio.getContext()) {
            TiffSaver saver = new TiffSaver(context, targetFile);
            saver.setBigTiff(false);
            saver.setWritingSequentially(true);
            saver.setLittleEndian(true);
            saver.writeHeader();
            System.out.printf("Creating %s...%n", targetFile);
            for (int ifdIndex = 0; ifdIndex < numberOfImages; ifdIndex++) {
                final int bandCount = 1;
                byte[] bytes = new byte[WIDTH * HEIGHT];
                for (int y = 0, disp = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++, disp++) {
                        bytes[disp] = (byte) (50 * ifdIndex + x + y);
                    }
                }
                ExtendedIFD ifd = new ExtendedIFD();
                ifd.putImageSizes(WIDTH, HEIGHT);
                // Note: IFD.LITTLE_ENDIAN is not necessary to be set, it is used only while reading
                ifd.putCompression(compression == null ? null : TiffCompression.valueOf(compression));
//                bytes = TiffParser.interleaveSamples(ifd, bytes, bytes.length);
                saver.writeImage(bytes, ifd, -1, ifd.getPixelType(), 0, 0, WIDTH, HEIGHT,
                        ifdIndex == numberOfImages - 1, bandCount, false);
            }
            saver.getStream().close();
        }
        System.out.println("Done");
    }

}
