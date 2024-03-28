/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2024 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.maps.pyramids.io.formats.common.recognition.tests;

import net.algart.arrays.Matrix;
import net.algart.arrays.UpdatableByteArray;
import net.algart.arrays.UpdatablePArray;
import net.algart.external.MatrixIO;
import net.algart.external.awt.BufferedImageToMatrix;
import net.algart.external.awt.MatrixToBufferedImage;
import net.algart.maps.pyramids.io.formats.common.recognition.BorderFinder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class BorderFinderTest {
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage:");
            System.out.println("    " + BorderFinder.class.getName() + " image-file x y "
                    + "[sizeForSearch [qualityMultiplier]]");
            System.out.println("where x, y is the estimation of left top corner of the border drawn at this image.");
            System.out.println("If sizeForSearch is not specified, it means searching across all image.");
            return;
        }
        final File file = new File(args[0]);
        final BufferedImage bufferedImage = ImageIO.read(file);
        if (bufferedImage == null) {
            throw new IOException("Cannot read " + file);
        }
        final Matrix<? extends UpdatablePArray> macro = new BufferedImageToMatrix.ToInterleavedRGB()
                .toMatrix(bufferedImage);
        final long leftTopX = Integer.parseInt(args[1]);
        final long leftTopY = Integer.parseInt(args[2]);
        final BorderFinder finder = new BorderFinder(macro);
        if (args.length >= 4) {
            final int sizeForSearch = Integer.parseInt(args[3]);
            finder.setSizeForSearchX(sizeForSearch);
            finder.setSizeForSearchY(sizeForSearch);
        }
        final double qualityMulriplier = args.length >= 5 ? Double.parseDouble(args[4]) : 1.0;
        finder.setLeftTopEstimation(leftTopX, leftTopY);
        System.out.printf("Processing %s...%n", file);
        final File resultFolder = new File(file.getParentFile(), "results");
        resultFolder.mkdir();
        long t1 = System.nanoTime();
        finder.preprocess();
        long t2 = System.nanoTime();
        finder.findLeftTop();
        long t3 = System.nanoTime();
        final String fileName = MatrixIO.removeExtension(file.getName());
        ImageIO.write(
                bufferedImage,
                "JPEG", new File(resultFolder, "result.source." + fileName + ".jpg"));
        ImageIO.write(
                new MatrixToBufferedImage.InterleavedRGBToInterleaved().toBufferedImage(finder.getAveraged()),
                "JPEG", new File(resultFolder, "result.averaged." + fileName + ".jpg"));
        final Matrix<UpdatableByteArray> allLeftTopQualities = finder.findAllLeftTopQualities(qualityMulriplier);
        ImageIO.write(
                new MatrixToBufferedImage.InterleavedRGBToInterleaved().toBufferedImage(allLeftTopQualities),
                "JPEG", new File(resultFolder, "result.ltq." + fileName + ".jpg"));
        System.out.printf(Locale.US, "Found position: (%d,%d); quality: %.5f; %.3f sec preprocess + %.3f sec search%n",
                finder.getResultLeftTopX(), finder.getResultLeftTopY(), finder.getResultLeftTopQuality(),
                (t2 - t1) * 1e-9, (t3 - t2) * 1e-9);
        ImageIO.write(
                new MatrixToBufferedImage.InterleavedRGBToInterleaved().toBufferedImage(
                        finder.drawResultLeftTopOnSlide(1.0)),
                "JPEG", new File(resultFolder, "result.lt." + fileName + ".jpg"));
    }
}
