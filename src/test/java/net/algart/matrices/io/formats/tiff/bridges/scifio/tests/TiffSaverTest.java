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
import io.scif.formats.tiff.TiffCompression;
import io.scif.util.FormatTools;
import net.algart.matrices.io.formats.tiff.bridges.scifio.ExtendedIFD;
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
                    "target.tif [number_of_images [compression]]");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex]);
        final int numberOfImages = startArgIndex + 1 < args.length ? Integer.parseInt(args[startArgIndex + 1]) : 1;
        final String compression = startArgIndex + 2 < args.length ? args[startArgIndex + 2] : null;
        final int bandCount = color ? 3 : 1;
        final int pixelType = FormatTools.UINT8;

        final SCIFIO scifio = new SCIFIO();
        try (Context context = scifio.getContext();
             TiffSaver saver = TiffSaver.getInstance(context, targetFile, !randomAccess && !append)) {
            saver.setWritingSequentially(!randomAccess);
            saver.setAppendToExisting(append);
            saver.setBigTiff(bigTiff);
            saver.setLittleEndian(true);
            saver.setAutoInterleave(true);
            saver.setJpegInPhotometricRGB(jpegRGB).setJpegQuality(0.8);
            if (singleStrip) {
                saver.setDefaultSingleStrip();
            } else {
                saver.setDefaultStripHeight(100);
            }
            saver.startWriting();
            System.out.printf("Creating %s...%n", targetFile);
            for (int ifdIndex = 0; ifdIndex < numberOfImages; ifdIndex++) {
                Object samplesArray = makeSamples(ifdIndex, bandCount, pixelType, WIDTH, HEIGHT);
                ExtendedIFD ifd = new ExtendedIFD();
                ifd.putImageSizes(WIDTH, HEIGHT);
                if (tiled) {
                    ifd.putTileSizes(64, 64);
                }
                //TODO!! custom pixelType
                ifd.putCompression(compression == null ? null : TiffCompression.valueOf(compression));
                if (planarSeparated) {
                    ifd.putIFDValue(IFD.PLANAR_CONFIGURATION, ExtendedIFD.PLANAR_CONFIG_SEPARATE);
                }
                saver.writeSamplesArray(ifd, samplesArray,
                        ifdIndex, bandCount, ifd.getPixelType(), 0, 0, WIDTH, HEIGHT,
                        ifdIndex == numberOfImages - 1);
            }
        }
        System.out.println("Done");
    }

    private static Object makeSamples(int ifdIndex, int bandCount, int pixelType, int xSize, int ySize) {
        final int matrixSize = xSize * ySize;
        byte[] bytes = new byte[matrixSize * bandCount];
        for (int y = 0, disp = 0; y < ySize; y++) {
            final int c = (y / 32) % bandCount;
            for (int x = 0; x < xSize; x++, disp++) {
                byte b = (byte) (50 * ifdIndex + x + y);
                bytes[disp + c * matrixSize] = b;
            }
        }
        return bytes;
    }

}
