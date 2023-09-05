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

package net.algart.matrices.maps.pyramids.io.formats.common.recognition;

import net.algart.arrays.*;
import net.algart.external.BufferedImageToMatrixConverter;
import net.algart.external.ExternalAlgorithmCaller;
import net.algart.external.MatrixToBufferedImageConverter;
import net.algart.math.IPoint;
import net.algart.math.Range;
import net.algart.math.functions.LinearFunc;
import net.algart.math.patterns.Patterns;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidSource;
import net.algart.matrices.morphology.BasicRankMorphology;
import net.algart.matrices.morphology.ContinuedRankMorphology;
import net.algart.matrices.morphology.RankPrecision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class BorderFinder {

    private int checkedLengthAlongBorderX = 100;
    private int checkedLengthAlongBorderY = 100;
    private int minLengthAlongBorder = 25;
    private int checkedSizeAtBackgroundX = 30;
    private int checkedSizeAtBackgroundY = 30;
    private double maxBrightnessVariationAlongBorder = 40 / 255.0;
    private double minBrightnessDifferenceFromBackground = 40 / 255.0;
    private double brightnessVariationAlongBorderCorrection = 10.0 / 255.0;
    private double brightnessDifferenceFromBackgroundCorrection = 10.0 / 255.0;

    private int sizeForSearchX = Integer.MAX_VALUE;
    private int sizeForSearchY = Integer.MAX_VALUE;

    private long leftTopEstimationX = 0;
    private long leftTopEstimationY = 0;

    private final Matrix<UpdatableByteArray> slide;
    // - this matrix is a simple 2-dimensional grayscale matrix, unlike 3-dimensional matrices from PlanePyramidSource
    private final long dimX;
    private final long dimY;
    private final byte[] slideBytes;
    private final int slideOfs;

    private Matrix<UpdatableByteArray> averaged = null;
    private long resultLeftTopX = -1;
    private long resultLeftTopY = -1;
    private double resultLeftTopQuality = Double.NaN;

    public BorderFinder(Matrix<? extends PArray> packedMacroImage) {
        if (packedMacroImage == null) {
            throw new NullPointerException("Null packed macro image");
        }
        this.slide = Arrays.SMM.newByteMatrix(
            packedMacroImage.dim(PlanePyramidSource.DIM_WIDTH),
            packedMacroImage.dim(PlanePyramidSource.DIM_HEIGHT));
        this.dimX = slide.dimX();
        this.dimY = slide.dimY();
        if (packedMacroImage.elementType() != byte.class) {
            final Range srcRange = Range.valueOf(0.0, packedMacroImage.array().maxPossibleValue(1.0));
            final Range destRange = Range.valueOf(0.0, Arrays.maxPossibleValue(ByteArray.class, 1.0));
            packedMacroImage = Matrices.asFuncMatrix(LinearFunc.getInstance(destRange, srcRange),
                ByteArray.class, packedMacroImage);
        }
        final Matrix<UpdatableByteArray> packedSlide = Matrices.matrix(slide.array(), 1, slide.dimX(), slide.dimY());
        if (packedMacroImage.dimEquals(packedSlide)) {
            Matrices.copy(null, packedSlide, packedMacroImage);
        } else {
            Matrices.resize(null, Matrices.ResizingMethod.AVERAGING, packedSlide, packedMacroImage);
            // - this "resizing" means just gray-scaling
        }
        final DirectAccessible da = (DirectAccessible) slide.array();
        assert da.hasJavaArray();
        assert dimX == (int) dimX;
        assert dimY == (int) dimY;
        // - because the matrix is direct accessible
        this.slideBytes = (byte[]) da.javaArray();
        this.slideOfs = da.javaArrayOffset();
    }

    // Configuration parameters:

    public int getCheckedLengthAlongBorderX() {
        return checkedLengthAlongBorderX;
    }

    public void setCheckedLengthAlongBorderX(int checkedLengthAlongBorderX) {
        if (checkedLengthAlongBorderX <= 0) {
            throw new IllegalArgumentException("Negative or zero checkedLengthAtBorderX = "
                + checkedLengthAlongBorderX);
        }
        this.checkedLengthAlongBorderX = checkedLengthAlongBorderX;
    }

    public int getCheckedLengthAlongBorderY() {
        return checkedLengthAlongBorderY;
    }

    public void setCheckedLengthAlongBorderY(int checkedLengthAlongBorderY) {
        if (checkedLengthAlongBorderY <= 0) {
            throw new IllegalArgumentException("Negative or zero checkedLengthAtBorderY = "
                + checkedLengthAlongBorderY);
        }
        this.checkedLengthAlongBorderY = checkedLengthAlongBorderY;
    }

    public int getMinLengthAlongBorder() {
        return minLengthAlongBorder;
    }

    public void setMinLengthAlongBorder(int minLengthAlongBorder) {
        if (minLengthAlongBorder <= 0) {
            throw new IllegalArgumentException("Negative or zero minLengthAlongBorder = " + minLengthAlongBorder);
        }
        this.minLengthAlongBorder = minLengthAlongBorder;
    }

    public int getCheckedSizeAtBackgroundX() {
        return checkedSizeAtBackgroundX;
    }

    public void setCheckedSizeAtBackgroundX(int checkedSizeAtBackgroundX) {
        if (checkedSizeAtBackgroundX <= 0) {
            throw new IllegalArgumentException("Negative or zero checkedSizeAtBackgroundX = "
                + checkedSizeAtBackgroundX);
        }
        this.checkedSizeAtBackgroundX = checkedSizeAtBackgroundX;
    }

    public int getCheckedSizeAtBackgroundY() {
        return checkedSizeAtBackgroundY;
    }

    public void setCheckedSizeAtBackgroundY(int checkedSizeAtBackgroundY) {
        if (checkedSizeAtBackgroundY <= 0) {
            throw new IllegalArgumentException("Negative or zero checkedSizeAtBackgroundY = "
                + checkedSizeAtBackgroundY);
        }
        this.checkedSizeAtBackgroundY = checkedSizeAtBackgroundY;
    }

    public double getMaxBrightnessVariationAlongBorder() {
        return maxBrightnessVariationAlongBorder;
    }

    public void setMaxBrightnessVariationAlongBorder(double maxBrightnessVariationAlongBorder) {
        if (maxBrightnessVariationAlongBorder < 0.0) {
            throw new IllegalArgumentException("Negative maxBrightnessVariationAlongBorder = "
                + maxBrightnessVariationAlongBorder);
        }
        this.maxBrightnessVariationAlongBorder = maxBrightnessVariationAlongBorder;
    }

    public double getMinBrightnessDifferenceFromBackground() {
        return minBrightnessDifferenceFromBackground;
    }

    public void setMinBrightnessDifferenceFromBackground(double minBrightnessDifferenceFromBackground) {
        if (minBrightnessDifferenceFromBackground < 0.0) {
            throw new IllegalArgumentException("Negative minBrightnessDifferenceFromBackground = "
                + minBrightnessDifferenceFromBackground);
        }
        this.minBrightnessDifferenceFromBackground = minBrightnessDifferenceFromBackground;
    }

    public double getBrightnessVariationAlongBorderCorrection() {
        return brightnessVariationAlongBorderCorrection;
    }

    public void setBrightnessVariationAlongBorderCorrection(double brightnessVariationAlongBorderCorrection) {
        this.brightnessVariationAlongBorderCorrection = brightnessVariationAlongBorderCorrection;
    }

    public double getBrightnessDifferenceFromBackgroundCorrection() {
        return brightnessDifferenceFromBackgroundCorrection;
    }

    public void setBrightnessDifferenceFromBackgroundCorrection(double brightnessDifferenceFromBackgroundCorrection) {
        this.brightnessDifferenceFromBackgroundCorrection = brightnessDifferenceFromBackgroundCorrection;
    }

    public int getSizeForSearchX() {
        return sizeForSearchX;
    }

    public void setSizeForSearchX(int sizeForSearchX) {
        if (sizeForSearchY < 0) {
            throw new IllegalArgumentException("Negative sizeForSearchY");
        }
        this.sizeForSearchX = sizeForSearchX;
    }

    public int getSizeForSearchY() {
        return sizeForSearchY;
    }

    public void setSizeForSearchY(int sizeForSearchY) {
        if (sizeForSearchX < 0) {
            throw new IllegalArgumentException("Negative sizeForSearchX");
        }
        this.sizeForSearchY = sizeForSearchY;
    }

    public void setAllConfigurationFromSystemProperties() {
        String s = System.getProperty(BorderFinder.class.getName() + ".checkedLengthAlongBorderX");
        if (s != null) {
            setCheckedLengthAlongBorderX(Integer.parseInt(s));
        }
        s = System.getProperty(BorderFinder.class.getName() + ".checkedLengthAlongBorderY");
        if (s != null) {
            setCheckedLengthAlongBorderY(Integer.parseInt(s));
        }
        s = System.getProperty(BorderFinder.class.getName() + ".minLengthAlongBorder");
        if (s != null) {
            setMinLengthAlongBorder(Integer.parseInt(s));
        }
        s = System.getProperty(BorderFinder.class.getName() + ".checkedSizeAtBackgroundX");
        if (s != null) {
            setCheckedSizeAtBackgroundX(Integer.parseInt(s));
        }
        s = System.getProperty(BorderFinder.class.getName() + ".checkedSizeAtBackgroundY");
        if (s != null) {
            setCheckedSizeAtBackgroundY(Integer.parseInt(s));
        }
        s = System.getProperty(BorderFinder.class.getName() + ".maxBrightnessVariationAlongBorder");
        if (s != null) {
            setMaxBrightnessVariationAlongBorder(Double.parseDouble(s));
        }
        s = System.getProperty(BorderFinder.class.getName() + ".minBrightnessDifferenceFromBackground");
        if (s != null) {
            setMinBrightnessDifferenceFromBackground(Double.parseDouble(s));
        }
        s = System.getProperty(BorderFinder.class.getName() + ".brightnessVariationAlongBorderCorrection");
        if (s != null) {
            setBrightnessVariationAlongBorderCorrection(Double.parseDouble(s));
        }
        s = System.getProperty(BorderFinder.class.getName() + ".brightnessDifferenceFromBackgroundCorrection");
        if (s != null) {
            setBrightnessDifferenceFromBackgroundCorrection(Double.parseDouble(s));
        }
        s = System.getProperty(BorderFinder.class.getName() + ".sizeForSearchX");
        if (s != null) {
            setSizeForSearchX(Integer.parseInt(s));
        }
        s = System.getProperty(BorderFinder.class.getName() + ".sizeForSearchY");
        if (s != null) {
            setSizeForSearchY(Integer.parseInt(s));
        }
    }

    // Basic control:

    public Matrix<UpdatableByteArray> getSlide() {
        return slide;
    }

    public Matrix<UpdatableByteArray> getAveraged() {
        return averaged;
    }

    public long getResultLeftTopX() {
        return resultLeftTopX;
    }

    public long getResultLeftTopY() {
        return resultLeftTopY;
    }

    public double getResultLeftTopQuality() {
        return resultLeftTopQuality;
    }

    public long getLeftTopEstimationX() {
        return leftTopEstimationX;
    }

    public long getLeftTopEstimationY() {
        return leftTopEstimationY;
    }

    public void setLeftTopEstimation(long x, long y) {
        this.leftTopEstimationX = x;
        this.leftTopEstimationY = y;
    }

    public long getDimX() {
        return dimX;
    }

    public long getDimY() {
        return dimY;
    }

    public void preprocess() {
        this.averaged = Arrays.SMM.newByteMatrix(dimX, dimY);
        final ContinuedRankMorphology morphology = ContinuedRankMorphology.getInstance(
            BasicRankMorphology.getInstance(null, 0.5, RankPrecision.BITS_8),
            Matrix.ContinuationMode.MIRROR_CYCLIC);
        final IPoint min = IPoint.valueOf(-checkedSizeAtBackgroundX / 2L, -checkedSizeAtBackgroundY / 2L);
        final IPoint max = min.add(IPoint.valueOf(checkedSizeAtBackgroundX - 1L, checkedSizeAtBackgroundY - 1L));
        morphology.dilation(averaged, slide, Patterns.newRectangularIntegerPattern(min, max));
    }

    public boolean findLeftTop() {
        if (!readyToProcess()) {
            throw new IllegalStateException(("Preprocessing was not performed"));
        }
        final long minX = Math.max(0, leftTopEstimationX - sizeForSearchX / 2);
        final long minY = Math.max(0, leftTopEstimationY - sizeForSearchY / 2);
        final long maxX = Math.min(dimX - 1 - checkedLengthAlongBorderX, leftTopEstimationX + sizeForSearchX / 2);
        final long maxY = Math.min(dimY - 1 - checkedLengthAlongBorderY, leftTopEstimationY + sizeForSearchY / 2);
        // Not too accurate: sizeForSearch=sizeForSearchY=2 really means 3x3 area. But symmetric.
        // Note that for an empty matrix we will have minX > maxX or minY > maxY.
        // Also note that we should not search last checkedLengthAtBorderX/Y pixels, in other case
        // the quality will increase to the bottom right corner, because the checked length will decrease.
        double bestQuality = Double.NaN;
        long bestX = -1;
        long bestY = -1;
        for (long y = minY; y <= maxY; y++) {
            for (long x = minX; x <= maxX; x++) {
                final double q = leftTopQuality(x, y);
                if (Double.isNaN(q)) {
                    continue;
                }
                if (Double.isNaN(bestQuality) || q > bestQuality) {
                    bestQuality = q;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        this.resultLeftTopQuality = bestQuality;
        this.resultLeftTopX = bestX;
        this.resultLeftTopY = bestY;
        if (!Double.isNaN(bestQuality)) {
            leftTopQuality(bestX, bestY);
            // - this call for debugging needs only
        }
        return !Double.isNaN(bestQuality);
    }

    public Matrix<UpdatableByteArray> findAllLeftTopQualities(double multiplier) {
        if (!readyToProcess()) {
            throw new IllegalStateException(("Preprocessing was not performed"));
        }
        final int minX = (int) Math.max(0, leftTopEstimationX - sizeForSearchX / 2);
        final int minY = (int) Math.max(0, leftTopEstimationY - sizeForSearchY / 2);
        final int maxX = (int) Math.min(dimX - 1 - checkedLengthAlongBorderX, leftTopEstimationX + sizeForSearchX / 2);
        final int maxY = (int) Math.min(dimY - 1 - checkedLengthAlongBorderY, leftTopEstimationY + sizeForSearchY / 2);
        byte[] result = new byte[(int) (dimX * dimY)];
        JArrays.fillByteArray(result, (byte) 128);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                final int ofs = y * (int) dimX + x;
                final double q = leftTopQuality(x, y);
                if (Double.isNaN(q)) {
                    result[ofs] = 0;
                } else {
                    assert q <= 0;
                    result[ofs] = (byte) Math.max(0.0, 255.0 + q * multiplier);
                }
            }
        }
        return Matrices.matrix(SimpleMemoryModel.asUpdatableByteArray(result), dimX, dimY);
    }

    public Matrix<UpdatableByteArray> drawResultLeftTopOnSlide(double color) {
        byte[] result = new byte[(int) (dimX * dimY)];
        slide.array().getData(0, result);
        if (!Double.isNaN(resultLeftTopQuality)) {
            final int th = 20;
            final long resultIndex = slide.index(resultLeftTopX, resultLeftTopY);
            final int minOrtogonalX = (int) Math.max(0, resultLeftTopX - th);
            final int minOrtogonalY = (int) Math.max(0, resultLeftTopY - th);
            final int maxOrtogonalX = (int) Math.min(dimX - 1, resultLeftTopX + th);
            final int maxOrtogonalY = (int) Math.min(dimY - 1, resultLeftTopY + th);
            final int toX = (int) Math.min(resultLeftTopX + (long) checkedLengthAlongBorderX, dimX - 1);
            for (int x = (int) resultLeftTopX, ofs = (int) (slideOfs + resultIndex); x < toX; x++, ofs++) {
                for (int y = minOrtogonalY; y <= maxOrtogonalY; y++) {
                    final long dy = y - resultLeftTopY;
                    result[ofs + (int) (dy * dimX)] = (byte) (color * 255.0 * (th - Math.abs(dy)) / (double) th);
                }
            }
            final int toY = (int) Math.min(resultLeftTopY + (long) checkedLengthAlongBorderY, dimY - 1);
            for (int y = (int) resultLeftTopY, ofs = (int) (slideOfs + resultIndex); y < toY; y++, ofs += dimX) {
                for (int x = minOrtogonalX; x <= maxOrtogonalX; x++) {
                    final long dx = x - resultLeftTopX;
                    result[ofs + (int) dx] = (byte) (color * 255.0 * (th - Math.abs(dx)) / (double) th);
                }
            }
        }
        return Matrices.matrix(SimpleMemoryModel.asUpdatableByteArray(result), dimX, dimY);
    }

    private boolean readyToProcess() {
        return averaged != null;
    }

    private double leftTopQuality(long checkedX, long checkedY) {
        assert checkedX >= 0 && checkedX < dimX;
        assert checkedY >= 0 && checkedY < dimY;
        // - so, we can be sure that the matrix is not empty
        assert checkedX == (int) checkedX;
        assert checkedY == (int) checkedY;
        // - because the slide matrix is direct-accessible
        final long checkedIndex = slide.index(checkedX, checkedY);
        final int cornerByte = slide.array().getByte(checkedIndex);
        final double corner = cornerByte / 255.0;
        final int averagedOutsideByte = averaged.array().getByte(averaged.index(
            Math.max(0, checkedX - (checkedSizeAtBackgroundX / 2 + 2)),
            Math.max(0, checkedY - (checkedSizeAtBackgroundY / 2 + 2))));
        final double averageOutside = averagedOutsideByte / 255.0;
        // "+ 2" to be on the safe side: it is really a square outside the border
        // "Math.max" is not the best idea, because if the border is near the matrix limit, the average value
        // will include the border itself; but I hope it is not too important for the median
        final int averagedInsideByte = averaged.array().getByte(averaged.index(
            Math.min(dimX - 1, checkedX + (checkedSizeAtBackgroundX / 2 + 2)),
            Math.min(dimY - 1, checkedY + (checkedSizeAtBackgroundY / 2 + 2))));
        final double averageInside = averagedInsideByte / 255.0;
        final double differenceFromBackground = Math.min(
            Math.abs(corner - averageOutside), Math.abs(corner - averageInside));
        if (differenceFromBackground < minBrightnessDifferenceFromBackground) {
            return Double.NaN;
        }
        int min = cornerByte;
        int max = cornerByte;
        final int minToX = (int) Math.min(checkedX + (long) minLengthAlongBorder, dimX - 1);
        int toX = (int) Math.min(checkedX + (long) checkedLengthAlongBorderX, dimX - 1);
        for (int x = (int) checkedX, ofs = (int) (slideOfs + checkedIndex); x < toX; x++, ofs++) {
            final int v = slideBytes[ofs] & 0xFF;
            if (Math.abs(v - averagedOutsideByte) < Math.abs(v - cornerByte)) {
                // the border length is probable less than checkedLengthAlongBorderX
                toX = Math.max((int) ((checkedX + x) / 2), minToX);
                // let's check untile the center between the current and start position:
                // maybe the line is just not strictly horizontal and we need to return essentially
                break;
            }
        }
        for (int x = (int) checkedX, ofs = (int) (slideOfs + checkedIndex); x < toX; x++, ofs++) {
            final int v = slideBytes[ofs] & 0xFF;
            min = min <= v ? min : v;
            max = max >= v ? max : v;
        }
        final int minToY = (int) Math.min(checkedY + (long) minLengthAlongBorder, dimY - 1);
        int toY = (int) Math.min(checkedY + (long) checkedLengthAlongBorderY, dimY - 1);
        for (int y = (int) checkedY, ofs = (int) (slideOfs + checkedIndex); y < toY; y++, ofs += dimX) {
            final int v = slideBytes[ofs] & 0xFF;
            if (Math.abs(v - averagedOutsideByte) < Math.abs(v - cornerByte)) {
                // the border length is probable less than checkedLengthAlongBorderX
                toY = Math.max((int) ((checkedY + y) / 2), minToY);
                // let's check untile the center between the current and start position:
                // maybe the line is just not strictly vertical and we need to return essentially
                break;
            }
        }
        for (int y = (int) checkedY, ofs = (int) (slideOfs + checkedIndex); y < toY; y++, ofs += dimX) {
            final int v = slideBytes[ofs] & 0xFF;
            min = min <= v ? min : v;
            max = max >= v ? max : v;
        }
        final double variationAlongBorder = (max - min) / 255.0;
        if (variationAlongBorder > maxBrightnessVariationAlongBorder) {
            return Double.NaN;
            // NaN is little more stable solution than Infinity (maybe in future we'll change the sign of the result)
        }
        return -(variationAlongBorder + brightnessVariationAlongBorderCorrection) /
            (differenceFromBackground + brightnessDifferenceFromBackgroundCorrection);
    }


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
        final Matrix<? extends UpdatablePArray> macro = new BufferedImageToMatrixConverter.ToPacked3D(false)
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
        final String fileName = ExternalAlgorithmCaller.removeFileExtension(file).getName();
        ImageIO.write(
            bufferedImage,
            "JPEG", new File(resultFolder, "result.source." + fileName + ".jpg"));
        ImageIO.write(
            new MatrixToBufferedImageConverter.Packed3DToPackedRGB(false).toBufferedImage(finder.getAveraged()),
            "JPEG", new File(resultFolder, "result.averaged." + fileName + ".jpg"));
        final Matrix<UpdatableByteArray> allLeftTopQualities = finder.findAllLeftTopQualities(qualityMulriplier);
        ImageIO.write(
            new MatrixToBufferedImageConverter.Packed3DToPackedRGB(false).toBufferedImage(allLeftTopQualities),
            "JPEG", new File(resultFolder, "result.ltq." + fileName + ".jpg"));
        System.out.printf(Locale.US, "Found position: (%d,%d); quality: %.5f; %.3f sec preprocess + %.3f sec search%n",
            finder.getResultLeftTopX(), finder.getResultLeftTopY(), finder.getResultLeftTopQuality(),
            (t2 - t1) * 1e-9, (t3 - t2) * 1e-9);
        ImageIO.write(
            new MatrixToBufferedImageConverter.Packed3DToPackedRGB(false).toBufferedImage(
                finder.drawResultLeftTopOnSlide(1.0)),
            "JPEG", new File(resultFolder, "result.lt." + fileName + ".jpg"));
    }
}
