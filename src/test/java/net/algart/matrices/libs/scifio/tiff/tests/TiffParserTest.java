/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014-2019 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.matrices.libs.scifio.tiff.tests;

import io.scif.FormatException;
import io.scif.SCIFIO;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.IFDList;
import io.scif.util.FormatTools;
import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.SimpleMemoryModel;
import net.algart.arrays.UpdatablePArray;
import net.algart.executors.api.data.SMat;
import net.algart.scifio.tiff.improvements.CachingTiffParser;
import net.algart.scifio.tiff.improvements.ExtendedTiffParser;
import net.algart.multimatrix.MultiMatrix;
import net.algart.multimatrix.MultiMatrix2D;
import net.algart.scifio.tiff.improvements.TiffParser;
import org.scijava.Context;
import org.scijava.io.location.FileLocation;
import org.scijava.util.Bytes;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public class TiffParserTest {
    private static final int MAX_IMAGE_DIM = 12000;

    private static final int START_X = 0;
    // - non-zero value allows to illustrate a bug in TiffParser.getSamples
    private static final int START_Y = 0;


    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        boolean pureScifio = false;
        boolean caching = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-pureScifio")) {
            pureScifio = true;
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-caching")) {
            caching = true;
            startArgIndex++;
        }

        if (args.length < startArgIndex + 3) {
            System.out.println("Usage:");
            System.out.println("    " + TiffParserTest.class.getName() + " [-pureScifio] [-caching] " +
                    "some_tiff_file ifdIndex result.png [x y width height [number-of-tests]]");
            return;
        }
        final Path tiffFile = Paths.get(args[startArgIndex++]);
        int ifdIndex = Integer.parseInt(args[startArgIndex++]);
        final File resultFile = new File(args[startArgIndex++]);
        final int x = args.length <= startArgIndex ? 0 : Integer.parseInt(args[startArgIndex]);
        final int y = args.length <= ++startArgIndex ? 0 : Integer.parseInt(args[startArgIndex]);
        int w = args.length <= ++startArgIndex ? -1 : Integer.parseInt(args[startArgIndex]);
        int h = args.length <= ++startArgIndex ? -1 : Integer.parseInt(args[startArgIndex]);
        final int numberOfTests = args.length <= ++startArgIndex ? 1 : Integer.parseInt(args[startArgIndex]);

        TiffInfo.showTiffInfo(tiffFile);

        final SCIFIO scifio = new SCIFIO();
        final Context context = scifio.getContext();
        final TiffParser parser = caching ?
                new CachingTiffParser(context, tiffFile).setFiller((byte) 0xC0) :
                !pureScifio ?
                        new ExtendedTiffParser(context, tiffFile).setFiller((byte) 0x80) :
                        new TiffParser(context, new FileLocation(tiffFile.toFile()));
        System.out.printf("Opening %s by %s...%n", tiffFile, parser);
        final IFDList ifds = parser.getIFDs();
        if (ifdIndex >= ifds.size()) {
            System.out.printf("%nNo IFD #%d, using last IFD #%d instead%n%n", ifdIndex, ifds.size() - 1);
            ifdIndex = ifds.size() - 1;
        }
        final IFD ifd = ifds.get(ifdIndex);
        if (w < 0) {
            w = (int) Math.min(ifd.getImageWidth(), MAX_IMAGE_DIM);
        }
        if (h < 0) {
            h = (int) Math.min(ifd.getImageLength(), MAX_IMAGE_DIM);
        }
        final int bandCount = ifd.getSamplesPerPixel();
        final int pixelType = ifd.getPixelType();

        Object array = null;
        for (int test = 1; test <= numberOfTests; test++) {
            if (test == 1) {
                System.out.printf("Reading data %dx%dx%d from %s%n",
                        w, h, bandCount, TiffInfo.ifdInfo(ifd, ifdIndex, ifds.size()));
            }
            if (parser instanceof ExtendedTiffParser extendedParser) {
                long t1 = System.nanoTime();
                array = extendedParser.readSamplesArray(ifd, x, y, w, h, true);
                long t2 = System.nanoTime();
                System.out.printf(Locale.US, "Test #%d: %dx%d loaded in %.3f ms%n",
                        test, w, h, (t2 - t1) * 1e-6);
            } else {
                long t1 = System.nanoTime();
                byte[] bytes = new byte[ExtendedTiffParser.sizeOfIFDRegion(ifd, w, h)];
                long t2 = System.nanoTime();
                parser.getSamples(ifd, bytes, x, y, w, h);
                long t3 = System.nanoTime();
                ExtendedTiffParser.correctUnusualPrecisions(ifd, bytes, w * h);
                long t4 = System.nanoTime();
                bytes = ExtendedTiffParser.interleaveSamples(ifd, bytes, w * h);
                long t5 = System.nanoTime();
                array = Bytes.makeArray(
                        bytes,
                        FormatTools.getBytesPerPixel(pixelType),
                        FormatTools.isFloatingPoint(pixelType),
                        ifd.isLittleEndian());
                long t6 = System.nanoTime();
                System.out.printf(Locale.US, "Test (usual) #%d: %dx%d (%.3f MB) loaded in %.3f ms = " +
                                "%.3f ms allocate + %.3f get + %.3f correct + %.3f interleave + %.3f make, " +
                                "%.3f MB/sec%n",
                        test, w, h, bytes.length / 1048576.0,
                        (t6 - t1) * 1e-6,
                        (t2 - t1) * 1e-6, (t3 - t2) * 1e-6, (t4 - t3) * 1e-6, (t5 - t4) * 1e-6, (t6 - t5) * 1e-6,
                        (bytes.length / 1048576.0 / ((t2 - t1) * 1e-9)));
            }
        }


        System.out.printf("Converting data to BufferedImage...%n");
        final BufferedImage image = arrayToImage(array, w, h, bandCount);
        System.out.printf("Saving result image into %s...%n", resultFile);
        if (!ImageIO.write(image, "png", resultFile)) {
            throw new IIOException("Cannot write " + resultFile);
        }
        context.close();
        System.out.println("Done");
    }

    private static BufferedImage arrayToImage(Object data, int sizeX, int sizeY, int bandCount) {
        Matrix<UpdatablePArray> matrix = Matrices.matrix(
                (UpdatablePArray) SimpleMemoryModel.asUpdatableArray(data),
                bandCount, sizeX, sizeY);
        MultiMatrix2D multiMatrix = MultiMatrix.valueOf2DRGBA(SMat.unpackBandsFromSequentialSamples(matrix));
        return SMat.valueOf(multiMatrix).toBufferedImage();
    }

}
