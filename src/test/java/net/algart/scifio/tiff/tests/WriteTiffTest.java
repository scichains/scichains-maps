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
import io.scif.formats.tiff.TiffCompression;
import net.algart.scifio.tiff.experimental.TiffSaver;
import net.algart.scifio.tiff.ExtendedIFD;
import org.scijava.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class WriteTiffTest {
    private final static int WIDTH = 501;
    private final static int HEIGHT = 513;

    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        boolean color = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-color")) {
            color = true;
            startArgIndex++;
        }
        boolean singleStrip = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-singleStrip")) {
            singleStrip = true;
            startArgIndex++;
        }
        boolean planarSeparate = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-planarSeparate")) {
            planarSeparate = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 1) {
            System.out.println("Usage:");
            System.out.println("    " + WriteTiffTest.class.getName()
                    + " target.tif [number_of_images [compression]]");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex]);
        final int numberOfImages = startArgIndex + 1 < args.length ? Integer.parseInt(args[startArgIndex + 1]) : 1;
        final String compression = startArgIndex + 2 < args.length ? args[startArgIndex + 2] : null;

        final SCIFIO scifio = new SCIFIO();
        Files.deleteIfExists(targetFile);
        try (Context context = scifio.getContext();
            TiffSaver saver = new TiffSaver(context, targetFile)) {
            saver.setBigTiff(false);
            saver.setWritingSequentially(true);
            saver.setLittleEndian(true);
            if (singleStrip) {
                saver.setDefaultSingleStrip();
            } else {
                saver.setDefaultStripHeight(100);
            }
            saver.writeHeader();
            System.out.printf("Creating %s...%n", targetFile);
            for (int ifdIndex = 0; ifdIndex < numberOfImages; ifdIndex++) {
                final int bandCount = color ? 3 : 1;
                byte[] bytes = new byte[WIDTH * HEIGHT * bandCount];
                for (int y = 0, disp = 0; y < HEIGHT; y++) {
                    final int c = (y / 32) % bandCount;
                    for (int x = 0; x < WIDTH; x++, disp += bandCount) {
                        byte b = (byte) (50 * ifdIndex + x + y);
                        bytes[disp + c] = b;
                    }
                }
                ExtendedIFD ifd = new ExtendedIFD();
                ifd.putImageSizes(WIDTH, HEIGHT);
                ifd.putCompression(compression == null ? null : TiffCompression.valueOf(compression));
                if (planarSeparate) {
                    ifd.putIFDValue(IFD.PLANAR_CONFIGURATION, ExtendedIFD.PLANAR_CONFIG_SEPARATE);
                }
                saver.writeImage(bytes, ifd, -1, ifd.getPixelType(), 0, 0, WIDTH, HEIGHT,
                        ifdIndex == numberOfImages - 1, bandCount, true);
            }
        }
/*
        try (Context context = scifio.getContext();
             TiffSaver saver = new TiffSaver(context, targetFile);
             SequentialTiffWriter writer = new SequentialTiffWriter(
                     context, Paths.get(targetFile + ".old.tiff"))) {
            saver.setBigTiff(false);
            saver.setWritingSequentially(true);
            saver.setLittleEndian(true);
            if (singleStrip) {
                saver.setDefaultSingleStrip();
            }
            saver.writeHeader();
            writer.setLittleEndian(true).open();
            System.out.printf("Creating %s...%n", targetFile);
            for (int ifdIndex = 0; ifdIndex < numberOfImages; ifdIndex++) {
                final int bandCount = color ? 3 : 1;
                byte[] bytes = new byte[WIDTH * HEIGHT * bandCount];
                for (int y = 0, disp = 0; y < HEIGHT; y++) {
                    final int c = (y / 32) % bandCount;
                    for (int x = 0; x < WIDTH; x++, disp += bandCount) {
                        byte b = (byte) (50 * ifdIndex + x + y);
                        bytes[disp + c] = b;
                    }
                }
                ExtendedIFD ifd = new ExtendedIFD();
                ifd.putImageSizes(WIDTH, HEIGHT);
                ifd.putCompression(compression == null ? null : TiffCompression.valueOf(compression));
//                bytes = TiffParser.interleaveSamples(ifd, bytes, bytes.length);
                saver.writeImage(bytes, ifd, -1, ifd.getPixelType(), 0, 0, WIDTH, HEIGHT,
                        ifdIndex == numberOfImages - 1, bandCount, false);
                writer.setCompression(TiffCompression.valueOf(compression));
                writer.setImageSizes(WIDTH, HEIGHT);
                writer.writeSeveralTilesOrStrips(bytes, ifd, ifd.getPixelType(), bandCount,
                        0, 0, WIDTH, HEIGHT,
                        true, ifdIndex == numberOfImages - 1);
            }
        }

 */
        System.out.println("Done");
    }

}
