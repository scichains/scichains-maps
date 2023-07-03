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
import io.scif.SCIFIO;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.PhotoInterp;
import net.algart.matrices.io.formats.tiff.bridges.scifio.experimental.SequentialTiffWriter;
import net.algart.matrices.io.formats.tiff.bridges.scifio.ExtendedIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffParser;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffSaver;
import org.scijava.Context;
import org.scijava.io.location.FileLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

public class ReadWriteTiffTest {
    private static final int MAX_IMAGE_DIM = 8000;
    private static final int START_X = 0;
    private static final int START_Y = 0;

    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        boolean bigTiff = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-big")) {
            bigTiff = true;
            startArgIndex++;
        }
        boolean jpegRGB = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-jpegRGB")) {
            jpegRGB = true;
            startArgIndex++;
        }
        boolean singleStrip = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-singleStrip")) {
            singleStrip = true;
            startArgIndex++;
        }
        boolean planar = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-planar")) {
            planar = true;
            startArgIndex++;
        }
        boolean legacy = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-legacy")) {
            legacy = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.println("    " + ReadWriteTiffTest.class.getName()
                    + " source.tif target.tif [firstIFDIndex lastIFDIndex [numberOfTests]]");
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex++]);
        final Path targetFile = Paths.get(args[startArgIndex++]);
        final int firstIFDIndex = startArgIndex < args.length ?
                Integer.parseInt(args[startArgIndex]) :
                0;
        int lastIFDIndex = startArgIndex + 1 < args.length ?
                Integer.parseInt(args[startArgIndex + 1]) :
                Integer.MAX_VALUE;
        final int numberOfTests = startArgIndex + 2 < args.length ? Integer.parseInt(args[startArgIndex + 2]) : 1;
        final Path targetExperimentalFile = targetFile.getParent().resolve("experimental_for_comparison.tiff");

        System.out.printf("Opening %s...%n", sourceFile);

        final SCIFIO scifio = new SCIFIO();
        for (int test = 1; test <= numberOfTests; test++) {
            System.out.printf("Test #%d%n", test);
            try (Context context = scifio.getContext()) {
                TiffParser parser = new TiffParser(context, sourceFile).setAutoUnpackUnusualPrecisions(true);
                parser.setFiller((byte) 0xC0);
                Files.deleteIfExists(targetFile);
                Files.deleteIfExists(targetExperimentalFile);
                // - strange, but necessary
                TiffSaver saver = new TiffSaver(context, new FileLocation(targetFile.toFile()));
                saver.setBigTiff(bigTiff);
                saver.setWritingSequentially(true);
                saver.setAutoInterleave(true);
                saver.setLittleEndian(true);
                saver.setCustomJpeg(true).setJpegInPhotometricRGB(jpegRGB).setJpegQuality(0.8);
                saver.writeHeader();
                // - necessary; SequentialTiffWriter does it automatically
                SequentialTiffWriter writer = legacy ? new SequentialTiffWriter(context, targetExperimentalFile)
                        .setBigTiff(bigTiff)
                        .setLittleEndian(true)
                        .open() :
                        null;
                System.out.printf("Writing %s%s...%n", targetFile, bigTiff ? " (big TIFF)" : "");
                final List<IFD> ifdList = parser.getIFDs();
                lastIFDIndex = Math.min(lastIFDIndex, ifdList.size() - 1);
                for (int ifdIndex = firstIFDIndex; ifdIndex <= lastIFDIndex; ifdIndex++) {
                    final boolean last = ifdIndex == ifdList.size() - 1;
                    final ExtendedIFD parserIFD = ExtendedIFD.extend(ifdList.get(ifdIndex));
                    System.out.printf("Copying #%d/%d:%n%s%n", ifdIndex, ifdList.size(), parserIFD);
                    final int w = (int) Math.min(parserIFD.getImageWidth(), MAX_IMAGE_DIM);
                    final int h = (int) Math.min(parserIFD.getImageLength(), MAX_IMAGE_DIM);
                    final int tileSizeX = parserIFD.getTileSizeX();
                    final int tileSizeY = parserIFD.getTileSizeY();

                    final int bandCount = parserIFD.getSamplesPerPixel();
                    long t1 = System.nanoTime();
                    byte[] bytes = parser.getSamples(parserIFD, null, START_X, START_Y, w, h);
                    long t2 = System.nanoTime();
                    ExtendedIFD saverIFD = new ExtendedIFD(parserIFD);
                    if (singleStrip) {
                        saverIFD.putIFDValue(IFD.ROWS_PER_STRIP, h);
                        // - not remove! Removing means default value!
                    }
                    saverIFD.putImageSizes(w, h);
                    saver.writeImage(bytes, saverIFD, -1, parserIFD.getPixelType(), START_X, START_Y, w, h,
                            last, bandCount, false);
                    long t3 = System.nanoTime();
                    System.out.printf("Effective IFD:%n%s%n", saverIFD);
                    System.out.printf(Locale.US,
                            "%dx%d (%.3f MB) read in %.3f ms (%.3f MB/s) and written in %.3f ms (%.3f MB/s)%n",
                            w, h, bytes.length / 1048576.0,
                            (t2 - t1) * 1e-6, bytes.length / 1048576.0 / ((t2 - t1) * 1e-9),
                            (t3 - t2) * 1e-6, bytes.length / 1048576.0 / ((t3 - t2) * 1e-9));

                    if (legacy) {
                        System.out.println();
                        final int paddedW = ((w + tileSizeX - 1) / tileSizeX) * tileSizeX;
                        int paddedH = ((h + tileSizeY - 1) / tileSizeY) * tileSizeY;
                        if (singleStrip) {
                            paddedH = h;
                        }
                        bytes = parser.getSamples(parserIFD, null, START_X, START_Y, paddedW, paddedH);
                        writer.setPhotometricInterpretation(PhotoInterp.Y_CB_CR);
                        // - necessary for correct colors in JPEG; ignored (overridden) by original TiffSaver

                        writer.setInterleaved(!planar);
                        writer.setCompression(parserIFD.getCompression());
                        writer.setImageSizes(w, h);
                        if (parserIFD.isTiled()) {
                            writer.setTiling(true);
                            writer.setTileSizes((int) parserIFD.getTileWidth(), (int) parserIFD.getTileLength());
                        } else {
                            writer.setTiling(false);
                        }
                        if (!planar) {
                            bytes = TiffParser.interleaveSamples(parserIFD, bytes, paddedW * paddedH);
                        }
                        saverIFD = new ExtendedIFD(PureScifioReadWriteTiffTest.removeUndesirableTags(parserIFD));
                        if (singleStrip) {
                            saverIFD.putIFDValue(IFD.ROWS_PER_STRIP, h);
                            // - not remove! Removing means default value!
                        }
                        writer.writeSeveralTilesOrStrips(bytes, saverIFD, parserIFD.getPixelType(), bandCount,
                                START_X, START_Y, paddedW, paddedH, true, last);
                        System.out.printf("Effective IFD (legacy):%n%s%n", saverIFD);
//                saver.setBigTiff(true);

//                ifdCopy.putIFDValue(IFD.PLANAR_CONFIGURATION, ExtendedIFD.PLANAR_CONFIG_SEPARATE);
//                ifdCopy.remove(IFD.STRIP_BYTE_COUNTS);
//                ifdCopy.remove(IFD.STRIP_OFFSETS);
                    }
                }
                saver.getStream().close();
                if (legacy) {
                    writer.close();
                }
            }
        }
        System.out.println("Done");
    }

}
