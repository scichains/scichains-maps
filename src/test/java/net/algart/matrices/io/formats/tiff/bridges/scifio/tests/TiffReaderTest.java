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
import net.algart.arrays.*;
import net.algart.executors.api.data.SMat;
import net.algart.matrices.io.formats.tiff.bridges.scifio.CachingTiffReader;
import net.algart.matrices.io.formats.tiff.bridges.scifio.DetailedIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffReader;
import net.algart.multimatrix.MultiMatrix;
import net.algart.multimatrix.MultiMatrix2D;
import org.scijava.Context;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TiffReaderTest {
    private static final int MAX_IMAGE_DIM = 8000;

    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        boolean noContext = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-noContext")) {
            noContext = true;
            startArgIndex++;
        }
        boolean cache = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-cache")) {
            cache = true;
            startArgIndex++;
        }
        boolean tiny = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-tiny")) {
            tiny = true;
            startArgIndex++;
        }

        if (args.length < startArgIndex + 3) {
            System.out.println("Usage:");
            System.out.println("    " + TiffReaderTest.class.getName() + " [-cache [-tiny]] " +
                    "some_tiff_file result.png ifdIndex " +
                    "[x y width height [number-of-tests] [number-of-complete-repeats]");
            return;
        }
        final Path tiffFile = Paths.get(args[startArgIndex++]);
        final File resultFile = new File(args[startArgIndex++]);
        int ifdIndex = Integer.parseInt(args[startArgIndex++]);
        final int x = args.length <= startArgIndex ? 0 : Integer.parseInt(args[startArgIndex]);
        final int y = args.length <= ++startArgIndex ? 0 : Integer.parseInt(args[startArgIndex]);
        int w = args.length <= ++startArgIndex ? -1 : Integer.parseInt(args[startArgIndex]);
        int h = args.length <= ++startArgIndex ? -1 : Integer.parseInt(args[startArgIndex]);
        final int numberOfTests = args.length <= ++startArgIndex ? 1 : Integer.parseInt(args[startArgIndex]);
        final int numberOfCompleteRepeats = args.length <= ++startArgIndex ? 1 : Integer.parseInt(args[startArgIndex]);

//        TiffInfo.showTiffInfo(tiffFile);

        for (int repeat = 1; repeat <= numberOfCompleteRepeats; repeat++) {
            try (final Context context = noContext ? null : new SCIFIO().getContext()) {
                long t1 = System.nanoTime();
                final TiffReader reader = cache ?
                        new CachingTiffReader(context, tiffFile)
                                .setMaxCachingMemory(tiny ? 1000000 : CachingTiffReader.DEFAULT_MAX_CACHING_MEMORY) :
                        new TiffReader(context, tiffFile);
                long t2 = System.nanoTime();
//            reader.setExtendedCodec(false);
                reader.setFiller((byte) 0x80);
                final var ifds = reader.allIFD();
                long t3 = System.nanoTime();
                System.out.printf("Opening %s by %s in: %.3f ms opening, %.3f ms reading IFDs%n",
                        tiffFile, reader,
                        (t2 - t1) * 1e-6,
                        (t3 - t2) * 1e-6);
                if (ifds.isEmpty()) {
                    System.out.println("No IFDs");
                    return;
                }
                if (ifdIndex >= ifds.size()) {
                    System.out.printf("%nNo IFD #%d, using last IFD #%d instead%n%n", ifdIndex, ifds.size() - 1);
                    ifdIndex = ifds.size() - 1;
                }
                final DetailedIFD ifd = ifds.get(ifdIndex);
                if (w < 0) {
                    w = (int) Math.min(ifd.getImageWidth(), MAX_IMAGE_DIM);
                }
                if (h < 0) {
                    h = (int) Math.min(ifd.getImageLength(), MAX_IMAGE_DIM);
                }
                final int bandCount = ifd.getSamplesPerPixel();

                Object array = null;
                for (int test = 1; test <= numberOfTests; test++) {
                    if (test == 1 && repeat == 1) {
                        System.out.printf("Reading data %dx%dx%d from %s%n",
                                w, h, bandCount, TiffInfo.ifdInfo(ifd, ifdIndex, ifds.size()));
                    }
                    t1 = System.nanoTime();
                    array = reader.readSamplesArray(ifd, x, y, w, h);
                    t2 = System.nanoTime();
                    System.out.printf(Locale.US, "Test #%d: %dx%d loaded in %.3f ms%n",
                            test, w, h, (t2 - t1) * 1e-6);
                    System.gc();
                }

                System.out.printf("Converting data to BufferedImage...%n");
                final BufferedImage image = unpackedArrayToImage(array, w, h, bandCount);
                System.out.printf("Saving result image into %s...%n", resultFile);
                if (!ImageIO.write(image, TiffInfo.extension(resultFile.getName(), "bmp"), resultFile)) {
                    throw new IIOException("Cannot write " + resultFile);
                }
            }
            System.out.printf("Done repeat %d/%d%n%n", repeat, numberOfCompleteRepeats);
        }
    }

    private static BufferedImage unpackedArrayToImage(Object data, int sizeX, int sizeY, int bandCount) {
        Matrix<UpdatablePArray> matrix = Matrices.matrix(
                (UpdatablePArray) SimpleMemoryModel.asUpdatableArray(data),
                sizeX, sizeY, bandCount);
        List<Matrix<? extends PArray>> channels = new ArrayList<>();
        for (int k = 0; k < bandCount; k++) {
            UpdatablePArray array = matrix.subMatr(0, 0, k, sizeX, sizeY, 1).array();
            channels.add(Matrices.matrix(array, sizeX, sizeY));
        }
        MultiMatrix2D multiMatrix = MultiMatrix.valueOf2DRGBA(channels);
        return SMat.valueOf(multiMatrix).toBufferedImage();
    }

}
