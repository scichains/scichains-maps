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
import io.scif.formats.tiff.FillOrder;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.TiffCompression;
import io.scif.util.FormatTools;
import net.algart.math.IRectangularArea;
import net.algart.matrices.io.formats.tiff.bridges.scifio.DetailedIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffReader;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffTools;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffWriter;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffMap;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffTile;
import org.scijava.Context;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class TiffWriterTest {
    private final static int IMAGE_WIDTH = 1011;
    private final static int IMAGE_HEIGHT = 1051;

    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        boolean noContext = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-noContext")) {
            noContext = true;
            startArgIndex++;
        }
        boolean resizable = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-resizable")) {
            resizable = true;
            startArgIndex++;
        }
        boolean interleaveOutside = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-interleaveOutside")) {
            interleaveOutside = true;
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
        boolean preserveOld = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-preserveOld")) {
            preserveOld = true;
            startArgIndex++;
        }
        boolean preserveOldAccurately = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-preserveOldAccurately")) {
            preserveOldAccurately = preserveOld = true;
            startArgIndex++;
        }
        boolean bigTiff = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-bigTiff")) {
            bigTiff = true;
            startArgIndex++;
        }
        boolean longTags = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-longTags")) {
            longTags = true;
            startArgIndex++;
        }
        boolean allowMissing = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-allowMissing")) {
            allowMissing = true;
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
        boolean predict = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-predict")) {
            predict = true;
            startArgIndex++;
        }
        boolean reverseBits = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-reverseBits")) {
            reverseBits = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.println("    " + TiffWriterTest.class.getName() +
                    " [-resizable] [-append] [-bigTiff] [-color] [-jpegRGB] [-singleStrip] " +
                    "[-tiled] [-planarSeparated] " +
                    "target.tiff unit8|int8|uint16|int16|uint32|int32|float|double [number_of_images [compression]]" +
                    "[x y width height [number_of_tests]]");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex++]);
        final int pixelType = switch (args[startArgIndex]) {
            case "uint8" -> FormatTools.UINT8;
            case "int8" -> FormatTools.INT8;
            case "uint16" -> FormatTools.UINT16;
            case "int16" -> FormatTools.INT16;
            case "uint32" -> FormatTools.UINT32;
            case "int32" -> FormatTools.INT32;
            case "float" -> FormatTools.FLOAT;
            case "double" -> FormatTools.DOUBLE;
            default -> throw new IllegalArgumentException("Unknown element type " + args[startArgIndex]);
        };

        final int numberOfImages = ++startArgIndex < args.length ? Integer.parseInt(args[startArgIndex]) : 1;
        final String compression = ++startArgIndex < args.length ? args[startArgIndex] : null;
        final int x = args.length <= ++startArgIndex ? 0 : Integer.parseInt(args[startArgIndex]);
        final int y = args.length <= ++startArgIndex ? 0 : Integer.parseInt(args[startArgIndex]);
        int w = args.length <= ++startArgIndex ? -1 : Integer.parseInt(args[startArgIndex]);
        int h = args.length <= ++startArgIndex ? -1 : Integer.parseInt(args[startArgIndex]);
        if (w < 0) {
            w = IMAGE_WIDTH - x;
        }
        if (h < 0) {
            h = IMAGE_HEIGHT - y;
        }
        final int numberOfTests = ++startArgIndex < args.length ? Integer.parseInt(args[startArgIndex]) : 1;
        final int firstIfdIndex = ++startArgIndex < args.length ? Integer.parseInt(args[startArgIndex]) : 0;
        final int numberOfChannels = color ? 3 : 1;
        if (planarSeparated) {
            // - we must not interleave data at all
            interleaveOutside = false;
        }
        final boolean existingFile = append || randomAccess;

        System.out.printf("%d images %s, %d total test%n",
                numberOfImages,
                randomAccess ? "from " + firstIfdIndex : "sequentially",
                numberOfTests);

        final SCIFIO scifio = new SCIFIO();
        for (int test = 1; test <= numberOfTests; test++) {
            try (final Context context = noContext ? null : scifio.getContext();
                 final TiffWriter writer = new TiffWriter(context, targetFile, !existingFile)) {
//                 TiffWriter writer = new TiffSaver(context, targetFile.toString())) {
//                writer.setExtendedCodec(false);
                if (interleaveOutside && FormatTools.getBytesPerPixel(pixelType) == 1) {
                    writer.setAutoInterleaveSource(false);
                }
//                writer.setWritingForwardAllowed(false);
                writer.setBigTiff(bigTiff);
//                writer.setLittleEndian(true);
                writer.setJpegInPhotometricRGB(jpegRGB);
//                writer.setJpegQuality(0.8);
//                writer.setPredefinedPhotoInterpretation(PhotoInterp.Y_CB_CR);
//                writer.setByteFiller((byte) 0xE0);
                writer.setTileInitializer(TiffWriterTest::customFillEmptyTile);
                writer.setMissingTilesAllowed(allowMissing);
                System.out.printf("%nTest #%d/%d: %s %s...%n",
                        test, numberOfTests,
                        existingFile ? "writing to" : "creating", targetFile);
                for (int k = 0; k < numberOfImages; k++) {
                    final int ifdIndex = firstIfdIndex + k;
                    DetailedIFD ifd = new DetailedIFD();
                    ifd.putImageDimensions(IMAGE_WIDTH, IMAGE_HEIGHT);
                    if (longTags) {
                        ifd.put(IFD.IMAGE_WIDTH, (long) IMAGE_WIDTH);
                        ifd.put(IFD.IMAGE_LENGTH, (long) IMAGE_HEIGHT);
                    }
                    // ifd.put(IFD.JPEG_TABLES, new byte[]{1, 2, 3, 4, 5});
                    // - some invalid field: must not affect non-JPEG formats
                    if (tiled) {
                        ifd.putTileSizes(112, 64);
                        if (longTags) {
                            ifd.put(IFD.TILE_WIDTH, (long) ifd.getTileSizeX());
                            ifd.put(IFD.TILE_LENGTH, (long) ifd.getTileSizeY());
                        }
                    } else if (!singleStrip) {
                        ifd.putStripSize(100);
                        if (longTags) {
                            ifd.put(IFD.ROWS_PER_STRIP, 100L);
                        }
                    }
                    ifd.putCompression(compression == null ? null : TiffCompression.valueOf(compression));
//                    if (jpegRGB) {
//                        // - alternative for setJpegInPhotometricRGB
//                        ifd.put(IFD.PHOTOMETRIC_INTERPRETATION, PhotoInterp.RGB.getCode());
//                    }
                    ifd.putPlanarSeparated(planarSeparated);
                    if (predict) {
                        ifd.put(IFD.PREDICTOR, DetailedIFD.PREDICTOR_HORIZONTAL);
                        // - unusual mode: no special putXxx method;
                        // should not be used for compressions besides LZW/DEFLATE
                    }
                    if (reverseBits) {
                        ifd.put(IFD.FILL_ORDER, FillOrder.REVERSED.getCode());
                        // - unusual mode: no special putXxx method
                    }
                    ifd.putPixelInformation(numberOfChannels, pixelType);
                    if (k == 0) {
                        if (existingFile) {
                            writer.startExistingFile();
                        } else {
                            writer.startNewFile();
                        }
                    }
                    final TiffMap map;
                    boolean overwriteExisting = randomAccess && k == 0;
                    if (overwriteExisting) {
                        // - Ignoring previous IFD. It has no sense for k > 0:
                        // after writing first IFD (at firstIfdIndex), new number of IFD
                        // will become firstIfdIndex+1, i.e. there is no more IFDs.
                        // Note: you CANNOT change properties (like color or grayscale) of image #firstIfdIndex,
                        // but following images will be written with new properties.
                        // Note: it seems that we need to "flush" current writer.getStream(),
                        // but DataHandle has not any analogs of flush() method.
                        try (TiffReader reader = new TiffReader(null, targetFile, false)) {
                            ifd = reader.readSingleIFD(ifdIndex);
                            ifd.setFileOffsetForWriting(ifd.getFileOffsetOfReading());
                        }
                    }
                    if (overwriteExisting && preserveOld) {
                        boolean breakOldChain = numberOfImages > 1;
                        // - if we add more than 1 image, they break the existing chain
                        // (not necessary, it is just a choice for this demo)
                        if (breakOldChain) {
                            ifd.setLastIFD();
                        }
                        map = writer.startExistingImage(ifd);
                        if (preserveOldAccurately) {
                            preloadPartiallyOverwrittenTiles(writer, map, x, y, w, h);
                        }
                    } else {
                        map = writer.newMap(ifd, resizable);
                    }

                    Object samplesArray = makeSamples(ifdIndex, map.numberOfChannels(), map.pixelType(), w, h);
                    if (interleaveOutside && map.bytesPerSample() == 1) {
                        samplesArray = TiffTools.toInterleavedSamples(
                                (byte[]) samplesArray, map.numberOfChannels(), 1, w * h);
                    }
                    writer.writeImage(map, samplesArray, x, y, w, h);
                    if (test == 1) {
                        if (map.hasUnset()) {
                            List<TiffTile> unset = map.tiles().stream().filter(TiffTile::hasUnset).toList();
                            List<TiffTile> partial = unset.stream().filter(TiffTile::hasStoredDataFileOffset).toList();
                            System.out.printf(
                                    "  Image #%d: %d tiles, %d are not completely filled, %d are partially filled%n",
                                    k, map.size(), unset.size(), partial.size());
                            for (TiffTile tile : partial) {
                                Collection<IRectangularArea> unsetArea = tile.getUnsetArea();
                                System.out.printf("      %s (%d area%s)%n", tile, unsetArea.size(),
                                        unsetArea.size() == 1 ? ": " + unsetArea.iterator().next() : "s");
                            }
                        } else {
                            System.out.printf("All %d tiles are completely filled%n", map.size());
                        }
                    }
                }
            }
        }
        System.out.println("Done");
    }

    private static void preloadPartiallyOverwrittenTiles(
            TiffWriter writer,
            TiffMap map,
            int fromX, int fromY, int sizeX, int sizeY)
            throws IOException, FormatException {
        final TiffReader reader = new TiffReader(null, writer.getStream());
        final IRectangularArea areaToWrite = IRectangularArea.valueOf(
                fromX, fromY, fromX + sizeX - 1, fromY + sizeY - 1);
        for (TiffTile tile : map.tiles()) {
            if (tile.rectangle().intersects(areaToWrite) && !areaToWrite.contains(tile.rectangle())) {
                final TiffTile existing = reader.readTile(tile.index());
                tile.setDecodedData(existing.getDecodedData());
            }
        }
    }

    private static void customFillEmptyTile(TiffTile tiffTile) {
        byte[] decoded = tiffTile.getDecodedData();
        Arrays.fill(decoded, tiffTile.bytesPerSample() == 1 ? (byte) 0xE0 : (byte) 0xFF);
        tiffTile.setInterleaved(true);
        final int pixel = tiffTile.bytesPerPixel();
        final int sizeX = tiffTile.getSizeX() * pixel;
        final int sizeY = tiffTile.getSizeY();
        Arrays.fill(decoded, 0, sizeX, (byte) 0);
        Arrays.fill(decoded, decoded.length - sizeX, decoded.length, (byte) 0);
        for (int k = 0; k < sizeY; k++) {
            Arrays.fill(decoded, k * sizeX, k * sizeX + pixel, (byte) 0);
            Arrays.fill(decoded, (k + 1) * sizeX - pixel, (k + 1) * sizeX, (byte) 0);
        }
        tiffTile.separateSamples();
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
