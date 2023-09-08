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
import net.algart.matrices.io.formats.tiff.bridges.scifio.DetailedIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffReader;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffWriter;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffMap;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffTile;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffTileIndex;
import org.scijava.Context;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffCopyTest {
    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        boolean noContext = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-noContext")) {
            noContext = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.println("    " + TiffCopyTest.class.getName()
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

        System.out.printf("Opening %s...%n", sourceFile);

        final SCIFIO scifio = new SCIFIO();
        for (int test = 1; test <= numberOfTests; test++) {
            System.out.printf("Test #%d%n", test);
            try (Context context = noContext ? null : scifio.getContext()) {
                TiffReader reader = new TiffReader(context, sourceFile);
                reader.setByteFiller((byte) 0xC0);
                TiffWriter writer = new TiffWriter(context, targetFile);
                writer.setBigTiff(reader.isBigTiff());
                writer.setLittleEndian(reader.isLittleEndian());
                writer.startNewFile();

                System.out.printf("Copying to %s...%n", targetFile);
                final List<DetailedIFD> ifds = reader.allIFDs();
                lastIFDIndex = Math.min(lastIFDIndex, ifds.size() - 1);
                for (int ifdIndex = firstIFDIndex; ifdIndex <= lastIFDIndex; ifdIndex++) {
                    final DetailedIFD readIFD = ifds.get(ifdIndex);
                    System.out.printf("\rCopying #%d/%d: %s%n", ifdIndex, ifds.size(), readIFD);

                    DetailedIFD writeIFD = new DetailedIFD(readIFD);
                    final TiffMap readMap = new TiffMap(readIFD);
                    final TiffMap writeMap = writer.startNewImage(writeIFD, false);
                    writer.writeForward(writeMap);
                    int k = 0, n = writeMap.size();
                    for (TiffTile targetTile : writeMap.tiles()) {
                        TiffTileIndex sourceTileIndex = readMap.copyIndex(targetTile.index());
                        // - note: targetTile.index() refers to writeIFD, where we still have no data
                        TiffTile sourceTile = reader.readTile(sourceTileIndex);
                        targetTile.setDecodedData(sourceTile.getDecodedData());
                        writeMap.put(targetTile);
                        writer.writeTile(targetTile);
                        System.out.printf("\rCopying tile %d/%d...\r", ++k, n);
                    }
                    writer.completeImage(writeMap);
                }
                reader.close();
                writer.close();
            }
        }
        System.out.println("Done");
    }
}
