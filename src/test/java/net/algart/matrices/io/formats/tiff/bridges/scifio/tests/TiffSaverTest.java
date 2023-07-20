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
import io.scif.formats.tiff.TiffCompression;
import io.scif.util.FormatTools;
import net.algart.matrices.io.formats.tiff.bridges.scifio.DetailedIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffSaver;
import org.scijava.Context;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffSaverTest {
    private final static int WIDTH = 1011;
    private final static int HEIGHT = 1031;

    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        boolean noContext = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-noContext")) {
            noContext = true;
            startArgIndex++;
        }
        boolean append = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-append")) {
            append = true;
            startArgIndex++;
        }
        boolean randomAccess = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-randomAccess")) {
            randomAccess = true;
            startArgIndex++;
        }
        boolean bigTiff = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-bigTiff")) {
            bigTiff = true;
            startArgIndex++;
        }
        boolean color = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-color")) {
            color = true;
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
        boolean tiled = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-tiled")) {
            tiled = true;
            startArgIndex++;
        }
        boolean planarSeparated = false;
        if (args.length > startArgIndex && args[startArgIndex].toLowerCase().matches("-(planarseparated|ps)")) {
            planarSeparated = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 1) {
            System.out.println("Usage:");
            System.out.println("    " + TiffSaverTest.class.getName() +
                    " [-append] [-bigTiff] [-color] [-jpegRGB] [-singleStrip] [-tiled] [-planarSeparated] " +
                    "target.tif unit8|int8|uint16|int16|uint32|int32|float|double [number_of_images [compression]]" +
                    "[numberOfTests]");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex]);
        final int pixelType = switch (args[startArgIndex + 1]) {
            case "uint8" -> FormatTools.UINT8;
            case "int8" -> FormatTools.INT8;
            case "uint16" -> FormatTools.UINT16;
            case "int16" -> FormatTools.INT16;
            case "uint32" -> FormatTools.UINT32;
            case "int32" -> FormatTools.INT32;
            case "float" -> FormatTools.FLOAT;
            case "double" -> FormatTools.DOUBLE;
            default -> throw new IllegalArgumentException("Unknown element type " + args[startArgIndex + 1]);
        };

        final int numberOfImages = startArgIndex + 2 < args.length ? Integer.parseInt(args[startArgIndex + 2]) : 1;
        final String compression = startArgIndex + 3 < args.length ? args[startArgIndex + 3] : null;
        final int numberOfTests = startArgIndex + 4 < args.length ? Integer.parseInt(args[startArgIndex + 4]) : 1;
        final int bandCount = color ? 3 : 1;

        final SCIFIO scifio = new SCIFIO();
        for (int test = 1; test <= numberOfTests; test++) {
            final boolean deleteExistingFile = !randomAccess && !append;
            try (Context context = noContext ? null : scifio.getContext();
                 TiffSaver saver = TiffSaver.getInstance(context, targetFile, deleteExistingFile)) {
//                saver.setExtendedCodec(false);
                saver.setWritingSequentially(!randomAccess);
                saver.setAppendToExisting(append);
                saver.setBigTiff(bigTiff);
                saver.setLittleEndian(true);
                saver.setAutoInterleave(true);
                saver.setJpegInPhotometricRGB(jpegRGB).setJpegQuality(0.8);
//                saver.setPredefinedPhotoInterpretation(PhotoInterp.Y_CB_CR);
                if (singleStrip) {
                    saver.setDefaultSingleStrip();
                } else {
                    saver.setDefaultStripHeight(100);
                }
                saver.startWriting();
                System.out.printf("%nTest #%d: creating %s...%n", test, targetFile);
                for (int ifdIndex = 0; ifdIndex < numberOfImages; ifdIndex++) {
                    Object samplesArray = makeSamples(ifdIndex, bandCount, pixelType, WIDTH, HEIGHT);
                    DetailedIFD ifd = new DetailedIFD();
                    ifd.putImageSizes(WIDTH, HEIGHT);
                    if (tiled) {
                        ifd.putTileSizes(64, 64);
                    }
                    ifd.putCompression(compression == null ? null : TiffCompression.valueOf(compression));
                    if (planarSeparated) {
                        ifd.putIFDValue(IFD.PLANAR_CONFIGURATION, DetailedIFD.PLANAR_CONFIG_SEPARATE);
                    }
                    saver.writeSamplesArray(ifd, samplesArray,
                            ifdIndex, bandCount, pixelType, 0, 0, WIDTH, HEIGHT,
                            ifdIndex == numberOfImages - 1);
                }
            }
        }
        System.out.println("Done");
    }

    private static Object makeSamples(int ifdIndex, int bandCount, int pixelType, int xSize, int ySize) {
        final int matrixSize = xSize * ySize;
        switch (pixelType) {
            case FormatTools.UINT8, FormatTools.INT8 -> {
                byte[] channels = new byte[matrixSize * bandCount];
                for (int y = 0, disp = 0; y < ySize; y++) {
                    final int c = (y / 32) % bandCount;
                    for (int x = 0; x < xSize; x++, disp++) {
                        channels[disp + c * matrixSize] = (byte) (50 * ifdIndex + x + y);
                    }
                }
                return channels;
            }
            case FormatTools.UINT16, FormatTools.INT16 -> {
                short[] channels = new short[matrixSize * bandCount];
                for (int y = 0, disp = 0; y < ySize; y++) {
                    final int c = (y / 32) % bandCount;
                    for (int x = 0; x < xSize; x++, disp++) {
                        channels[disp + c * matrixSize] = (short) (256 * (50 * ifdIndex + x + y));
                    }
                }
                return channels;
            }
            case FormatTools.INT32, FormatTools.UINT32 -> {
                int[] channels = new int[matrixSize * bandCount];
                for (int y = 0, disp = 0; y < ySize; y++) {
                    final int c = (y / 32) % bandCount;
                    for (int x = 0; x < xSize; x++, disp++) {
                        channels[disp + c * matrixSize] = 256 * 65536 * (50 * ifdIndex + x + y);
                    }
                }
                return channels;
            }
            case FormatTools.FLOAT -> {
                float[] channels = new float[matrixSize * bandCount];
                for (int y = 0, disp = 0; y < ySize; y++) {
                    final int c = (y / 32) % bandCount;
                    for (int x = 0; x < xSize; x++, disp++) {
                        int v = (50 * ifdIndex + x + y) & 0xFF;
                        channels[disp + c * matrixSize] = (float) (0.5 + 1.5 * (v / 256.0 - 0.5));
                    }
                }
                return channels;
            }
            case FormatTools.DOUBLE -> {
                double[] channels = new double[matrixSize * bandCount];
                for (int y = 0, disp = 0; y < ySize; y++) {
                    final int c = (y / 32) % bandCount;
                    for (int x = 0; x < xSize; x++, disp++) {
                        int v = (50 * ifdIndex + x + y) & 0xFF;
                        channels[disp + c * matrixSize] = (float) (0.5 + 1.5 * (v / 256.0 - 0.5));
                    }
                }
                return channels;
            }
        }
        throw new UnsupportedOperationException("Unsupported pixelType = " + pixelType);
    }
}
