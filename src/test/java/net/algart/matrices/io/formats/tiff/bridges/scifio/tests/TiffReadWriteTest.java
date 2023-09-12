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
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffReader;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffTools;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffWriter;
import net.algart.matrices.io.formats.tiff.bridges.scifio.compatibility.TiffParser;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tests.nobridge.PureScifioTiffReadWriteTest;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffMap;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
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
        boolean compatibility = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-compatibility")) {
            compatibility = true;
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
                reader.setByteFiller((byte) 0xC0);
                TiffWriter writer = new TiffWriter(context, targetFile);
                writer.setBigTiff(bigTiff);
                writer.setLittleEndian(true);
                writer.setJpegInPhotometricRGB(jpegRGB).setJpegQuality(0.8);
                writer.startNewFile();

                TiffParser parser = null;
                io.scif.formats.tiff.TiffParser originalParser = null;
                io.scif.formats.tiff.TiffSaver sequentialTiffSaver = null;
                if (compatibility) {
                    if (context == null) {
                        throw new UnsupportedEncodingException(
                                "No-context mode is not supported by compatibility code");
                    }
                    FileLocation location = new FileLocation(sourceFile.toFile());
                    parser = new TiffParser(context, location);
                    originalParser = new io.scif.formats.tiff.TiffParser(context, location);

                    Files.deleteIfExists(targetExperimentalFile);
                    // - strange, but necessary
                    sequentialTiffSaver = new io.scif.formats.tiff.TiffSaver(context,
                            new FileLocation(targetExperimentalFile.toFile()));
                    sequentialTiffSaver.setWritingSequentially(true);
                    sequentialTiffSaver.setBigTiff(writer.isBigTiff());
                    sequentialTiffSaver.setLittleEndian(writer.isLittleEndian());
                    sequentialTiffSaver.writeHeader();
                }
                System.out.printf("Writing %s%s...%n", targetFile, bigTiff ? " (big TIFF)" : "");
                final List<DetailedIFD> ifds = reader.allIFDs();
                lastIFDIndex = Math.min(lastIFDIndex, ifds.size() - 1);
                for (int ifdIndex = firstIFDIndex; ifdIndex <= lastIFDIndex; ifdIndex++) {
                    final DetailedIFD readerIFD = ifds.get(ifdIndex);
                    System.out.printf("Copying #%d/%d:%n%s%n", ifdIndex, ifds.size(), readerIFD);
                    final int w = (int) Math.min((long) readerIFD.getImageDimX(), MAX_IMAGE_DIM);
                    final int h = (int) Math.min(readerIFD.getImageLength(), MAX_IMAGE_DIM);
                    final int tileSizeX = readerIFD.getTileSizeX();
                    final int tileSizeY = readerIFD.getTileSizeY();

                    final int bandCount = readerIFD.getSamplesPerPixel();
                    long t1 = System.nanoTime();
                    byte[] bytes = reader.readSamples(reader.newMap(readerIFD), START_X, START_Y, w, h);
                    long t2 = System.nanoTime();
                    DetailedIFD writerIFD = new DetailedIFD(readerIFD);
                    if (singleStrip) {
                        writerIFD.putIFDValue(IFD.ROWS_PER_STRIP, h);
                        // - not remove! Removing means default value!
                    }
                    writerIFD.putImageDimensions(w, h);
                    final TiffMap map = writer.newMap(writerIFD, false);
                    writer.writeSamples(map, bytes, START_X, START_Y, w, h);
                    long t3 = System.nanoTime();
                    System.out.printf("Effective IFD:%n%s%n", writerIFD);
                    System.out.printf(Locale.US,
                            "%dx%d (%.3f MB) read in %.3f ms (%.3f MB/s) and written in %.3f ms (%.3f MB/s)%n",
                            w, h, bytes.length / 1048576.0,
                            (t2 - t1) * 1e-6, bytes.length / 1048576.0 / ((t2 - t1) * 1e-9),
                            (t3 - t2) * 1e-6, bytes.length / 1048576.0 / ((t3 - t2) * 1e-9));

                    if (compatibility) {
                        System.out.println();
                        final int paddedW = ((w + tileSizeX - 1) / tileSizeX) * tileSizeX;
                        int paddedH = ((h + tileSizeY - 1) / tileSizeY) * tileSizeY;
                        if (singleStrip) {
                            paddedH = h;
                        }
                        final int samplesPerPixel = readerIFD.getSamplesPerPixel();
                        final int bytesPerSample = FormatTools.getBytesPerPixel(readerIFD.getPixelType());
                        bytes = new byte[paddedW * paddedH * samplesPerPixel * bytesPerSample];
                        @SuppressWarnings("deprecation")
                        byte[] buf1 = parser.getSamples(readerIFD, bytes, START_X, START_Y, paddedW, paddedH);
                        // - this deprecated method is implemented via new methods
                        // and actually equivalent to TiffReader.readSamples
                        assert buf1 == bytes;
                        buf1 = new byte[bytes.length];
                        byte[] buf2 = new byte[bytes.length];
                        //noinspection deprecation
                        parser.getSamples(readerIFD, buf1, START_X, START_Y, paddedW, paddedH,
                                0, 0);
                        // - this deprecated method is a legacy code from old class
                        originalParser.getSamples(readerIFD, buf2, START_X, START_Y, paddedW, paddedH);
                        // - this is absolute old TiffParser
                        if (!planar) {
                            bytes = TiffTools.toInterleavedSamples(
                                    bytes, samplesPerPixel, bytesPerSample, paddedW * paddedH);
                            buf1 = TiffTools.toInterleavedSamples(
                                    buf1, samplesPerPixel, bytesPerSample, paddedW * paddedH);
                            buf2 = TiffTools.toInterleavedSamples(
                                    buf2, samplesPerPixel, bytesPerSample, paddedW * paddedH);
                        }
                        final int checkedLength = paddedW * h * samplesPerPixel * bytesPerSample;
                        // - In a case of stripped image (but not tiled!), the last strip is usually stored
                        // with its actual sizes, i.e. with the height R = min(tileSizeY, imageLength-y).
                        // It means that the end part of the tile buffer inside TiffParser - after 1st R lines -
                        // is not defined: it may be filled anyhow.
                        // Old code reuses the same buffer for all tiles, but compatibility TiffParser.getTile method,
                        // based on TiffReader.readTile, always creates new zero-filled buffer.
                        // Only first R lines of this buffer are filled correctly, but this buffer
                        // is copied completely into getSamples byte[] argument.
                        // It is the reason why we should check only first h lines while comparing with the old parser.
                        // It is also the reason why we should perform interleaving before comparison.
                        if (!Arrays.equals(buf1, bytes) ||
                                !Arrays.equals(buf2, 0, checkedLength, bytes, 0, checkedLength)) {
                            compareResults(buf1, bytes, "Other parsing matrix");
                            compareResults(buf2, bytes, "Old parser");
                            throw new AssertionError();
                        }
                        writerIFD = new DetailedIFD(PureScifioTiffReadWriteTest.removeUndesirableTags(readerIFD));
                        if (singleStrip) {
                            writerIFD.putIFDValue(IFD.ROWS_PER_STRIP, h);
                            // - not remove! Removing means default value!
                        }
                        writerIFD.putImageDimensions(w, h);
                        // Note: as a result, last strip in this file will be too large!
                        // It is a minor inconsistency, but detected by GIMP and other programs.
                        writeSeveralTilesOrStrips(sequentialTiffSaver, buf2,
                                writerIFD, readerIFD.getPixelType(), bandCount,
                                START_X, START_Y, paddedW, paddedH, ifdIndex == lastIFDIndex);
                        System.out.printf("Effective IFD (compatibility):%n%s%n", writerIFD);
                    }
                }
                reader.close();
                writer.close();
                if (compatibility) {
                    parser.close();
                    sequentialTiffSaver.getStream().close();
                }
            }
        }
        System.out.println("Done");
    }

    private static void writeSeveralTilesOrStrips(
            final io.scif.formats.tiff.TiffSaver saver,
            final byte[] data, final IFD ifd,
            final int pixelType, int bandCount,
            final int lefTopX, final int leftTopY,
            final int width, final int height,
            final boolean lastImageInTiff) throws FormatException, IOException {
        ifd.remove(IFD.STRIP_OFFSETS);
        ifd.remove(IFD.STRIP_BYTE_COUNTS);
        ifd.remove(IFD.TILE_OFFSETS);
        ifd.remove(IFD.TILE_BYTE_COUNTS);
        // - enforces TiffSaver to recalculate these fields
        if (ifd.getCompression() == TiffCompression.JPEG) {
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.Y_CB_CR);
        }
        DataHandle<Location> out = saver.getStream();
        final long fp = out.offset();
        final PhotoInterp requestedPhotoInterp = ifd.containsKey(IFD.PHOTOMETRIC_INTERPRETATION) ?
                ifd.getPhotometricInterpretation() :
                null;
        saver.writeImage(
                data, ifd,
                -1, pixelType, lefTopX, leftTopY, width, height, lastImageInTiff, bandCount,
                false);
        // - copyDirectly = true is a BUG for PLANAR_CONFIG_SEPARATE
        // - planeIndex = -1 is not used in Writing-Sequentially mode
        if (ifd.getCompression() == TiffCompression.JPEG &&
                bandCount > 1
                && requestedPhotoInterp == PhotoInterp.Y_CB_CR
                && ifd.getPhotometricInterpretation() == PhotoInterp.RGB) {
            out.seek(fp);
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, requestedPhotoInterp.getCode());
            saver.writeIFD(ifd, lastImageInTiff ? 0 : out.length());
            // - I don't know why, but we need to replace RGB photometric, automatically
            // set by TiffSaver, with YCbCr, in other case this image is shown incorrectly.
            // We must do this here, not before writeImage: writeImage automatically sets it to RGB.
        }
        out.seek(out.length());
        // - this stupid SCIFIO class requires this little help to work correctly
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
            System.err.printf("%n%s: different behaviour! %d bytes differ since %d, summary difference %d%n",
                    message, count, first, sum);
        }
    }

}
