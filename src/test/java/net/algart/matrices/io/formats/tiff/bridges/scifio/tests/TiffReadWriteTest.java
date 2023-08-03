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
import io.scif.util.FormatTools;
import net.algart.matrices.io.formats.tiff.bridges.scifio.DetailedIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffReader;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffTools;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffWriter;
import net.algart.matrices.io.formats.tiff.bridges.scifio.compatibility.TiffParser;
import net.algart.matrices.io.formats.tiff.bridges.scifio.experimental.SequentialTiffWriter;
import org.scijava.Context;
import org.scijava.io.location.FileLocation;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class TiffReadWriteTest {
    private static final int MAX_IMAGE_DIM = 8000;
    private static final int START_X = 0;
    private static final int START_Y = 0;

    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        boolean noContext = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-noContext")) {
            noContext = true;
            startArgIndex++;
        }
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
            System.out.println("    " + TiffReadWriteTest.class.getName()
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
            try (Context context = noContext ? null : scifio.getContext()) {
                TiffReader reader = new TiffReader(context, sourceFile);
                reader.setFiller((byte) 0xC0);
                TiffWriter writer = new TiffWriter(context, targetFile);
                writer.setBigTiff(bigTiff);
                writer.setLittleEndian(true);
                writer.setJpegInPhotometricRGB(jpegRGB).setJpegQuality(0.8);
                writer.startWritingFile();

                TiffParser parser = null;
                io.scif.formats.tiff.TiffParser originalParser = null;
                SequentialTiffWriter sequentialTiffWriter = null;
                if (legacy) {
                    FileLocation locaion = new FileLocation(sourceFile.toFile());
                    parser = new TiffParser(context, locaion);
                    originalParser = new io.scif.formats.tiff.TiffParser(context, locaion);
                    sequentialTiffWriter = new SequentialTiffWriter(context, targetExperimentalFile)
                            .setBigTiff(bigTiff)
                            .setLittleEndian(true)
                            .open();
                }
                System.out.printf("Writing %s%s...%n", targetFile, bigTiff ? " (big TIFF)" : "");
                final List<DetailedIFD> ifdList = reader.allIFD();
                lastIFDIndex = Math.min(lastIFDIndex, ifdList.size() - 1);
                for (int ifdIndex = firstIFDIndex; ifdIndex <= lastIFDIndex; ifdIndex++) {
                    final boolean last = ifdIndex == ifdList.size() - 1;
                    final DetailedIFD parserIFD = ifdList.get(ifdIndex);
                    System.out.printf("Copying #%d/%d:%n%s%n", ifdIndex, ifdList.size(), parserIFD);
                    final int w = (int) Math.min(parserIFD.getImageWidth(), MAX_IMAGE_DIM);
                    final int h = (int) Math.min(parserIFD.getImageLength(), MAX_IMAGE_DIM);
                    final int tileSizeX = parserIFD.getTileSizeX();
                    final int tileSizeY = parserIFD.getTileSizeY();

                    final int bandCount = parserIFD.getSamplesPerPixel();
                    long t1 = System.nanoTime();
                    byte[] bytes = reader.readSamples(parserIFD, START_X, START_Y, w, h);
                    long t2 = System.nanoTime();
                    DetailedIFD saverIFD = new DetailedIFD(parserIFD);
                    if (singleStrip) {
                        saverIFD.putIFDValue(IFD.ROWS_PER_STRIP, h);
                        // - not remove! Removing means default value!
                    }
                    saverIFD.putImageSizes(w, h);
                    writer.writeSamples(saverIFD, bytes, null, bandCount,
                            parserIFD.getPixelType(), START_X, START_Y, w, h, last);
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
                        bytes = new byte[paddedW * paddedH *
                                parserIFD.getSamplesPerPixel() *
                                FormatTools.getBytesPerPixel(parserIFD.getPixelType())];
                        @SuppressWarnings("deprecation")
                        byte[] buf1 = parser.getSamples(parserIFD, bytes, START_X, START_Y, paddedW, paddedH);
                        // - this deprecated method is implemented via new methods
                        assert buf1 == bytes;
                        buf1 = new byte[bytes.length];
                        byte[] buf2 = new byte[bytes.length];
                        //noinspection deprecation
                        parser.getSamples(parserIFD, bytes, START_X, START_Y, paddedW, paddedH,
                                0, 0);
                        // - this deprecated method is a legacy from old code
                        originalParser.getSamples(parserIFD, bytes, START_X, START_Y, paddedW, paddedH);
                        if (!Arrays.equals(buf1, bytes) || !Arrays.equals(buf2, bytes)) {
                            compareResults(buf1, bytes, "Other parsing matrix");
                            compareResults(buf1, buf2, "Old parser");
                            throw new AssertionError();
                        }
                        sequentialTiffWriter.setPhotometricInterpretation(PhotoInterp.Y_CB_CR);
                        // - necessary for correct colors in JPEG; ignored (overridden) by original TiffSaver

                        sequentialTiffWriter.setInterleaved(!planar);
                        sequentialTiffWriter.setCompression(parserIFD.getCompression());
                        sequentialTiffWriter.setImageSizes(w, h);
                        if (parserIFD.isTiled()) {
                            sequentialTiffWriter.setTiling(true);
                            sequentialTiffWriter.setTileSizes((int) parserIFD.getTileWidth(), (int) parserIFD.getTileLength());
                        } else {
                            sequentialTiffWriter.setTiling(false);
                        }
                        if (!planar) {
                            int numberOfChannels = parserIFD.getSamplesPerPixel();
                            int bytesPerSample = FormatTools.getBytesPerPixel(parserIFD.getPixelType());
                            bytes = TiffTools.toInterleavedSamples(
                                    bytes, numberOfChannels, bytesPerSample, paddedW * paddedH);
                        }
                        saverIFD = new DetailedIFD(PureScifioTiffReadWriteTest.removeUndesirableTags(parserIFD));
                        if (singleStrip) {
                            saverIFD.putIFDValue(IFD.ROWS_PER_STRIP, h);
                            // - not remove! Removing means default value!
                        }
                        sequentialTiffWriter.writeSeveralTilesOrStrips(bytes, saverIFD, parserIFD.getPixelType(), bandCount,
                                START_X, START_Y, paddedW, paddedH, true, last);
                        System.out.printf("Effective IFD (legacy):%n%s%n", saverIFD);
//                writer.setBigTiff(true);

//                ifdCopy.putIFDValue(IFD.PLANAR_CONFIGURATION, ExtendedIFD.PLANAR_CONFIG_SEPARATE);
//                ifdCopy.remove(IFD.STRIP_BYTE_COUNTS);
//                ifdCopy.remove(IFD.STRIP_OFFSETS);
                    }
                }
                reader.close();
                writer.close();
                if (legacy) {
                    parser.close();
                    sequentialTiffWriter.close();
                }
            }
        }
        System.out.println("Done");
    }

    private static void compareResults(byte[] buf1, byte[] bytes, String message) {
        long sum = 0;
        int count = 0;
        int first = -1;
        for (int k = 0; k < buf1.length; k++) {
            if (buf1[k] != bytes[k]) {
                count++;
                if (first < 0) {
                    first = k;
                }
            }
            sum += Math.abs((buf1[k] & 0xFF) - (bytes[k] & 0xFF));
        }
        if (count > 0) {
            System.err.println(message + ": different behaviour! " + count +
                    " bytes differ since " + first + ", summary difference " + sum);
        }
    }

}
