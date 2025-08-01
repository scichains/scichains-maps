/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2025 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.maps.pyramids.io.formats.sources.svs;

import net.algart.arrays.Arrays;
import net.algart.maps.pyramids.io.api.PlanePyramidSource;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.TiffMap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public final class SVSIFDClassifier {
    private static final int THUMBNAIL_IFD_INDEX = 1;
    private static final int MAX_PIXEL_COUNT_IN_SPECIAL_IMAGES = 2048 * 2048;
    private static final double STANDARD_MACRO_ASPECT_RATIO = 2.8846153846153846153846153846154;
    // - 75000/26000, typical value for medicine
    private static final double ALLOWED_ASPECT_RATION_DEVIATION = 0.2;
    private static final boolean ALWAYS_USE_SVS_SPECIFICATION_FOR_LABEL_AND_MACRO =
            Arrays.SystemSettings.getBooleanEnv(
                    "ALWAYS_USE_SVS_SPECIFICATION_FOR_LABEL_AND_MACRO", false);

    private static final System.Logger LOG = System.getLogger(SVSPlanePyramidSource.class.getName());

    private final List<? extends TiffMap> maps;
    private final int ifdCount;
    private int thumbnailIndex = -1;
    private int labelIndex = -1;
    private int macroIndex = -1;
    private final List<Integer> unknownSpecialIndexes = new ArrayList<>();

    public SVSIFDClassifier(List<? extends TiffMap> maps) throws TiffException {
        Objects.requireNonNull(maps);
        this.maps = maps;
        this.ifdCount = maps.size();
        detectThumbnail();
        if (!detectTwoLastImages()) {
            detectSingleLastImage();
        }
        for (int k = THUMBNAIL_IFD_INDEX; k < ifdCount; k++) {
            if (isSpecial(k)) {
                continue;
            }
            if (isSmallImage(this.maps.get(k).ifd())) {
                unknownSpecialIndexes.add(k);
            }
        }
    }

    public boolean isSpecial(int ifdIndex) {
        if (ifdIndex < 0) {
            throw new IllegalArgumentException("Negative ifdIndex");
        }
        return ifdIndex == thumbnailIndex || ifdIndex == labelIndex || ifdIndex == macroIndex;
    }

    public boolean isUnknownSpecial(int ifdIndex) {
        // Little inefficient, but not important for real TIFF files (number of IFDs cannot be too large)
        return unknownSpecialIndexes.contains(ifdIndex);
    }

    public boolean hasThumbnail() {
        return thumbnailIndex != -1;
    }

    public int getThumbnailIndex() {
        return thumbnailIndex;
    }

    public boolean hasLabel() {
        return labelIndex != -1;
    }

    public int getLabelIndex() {
        return labelIndex;
    }

    public boolean hasMacro() {
        return macroIndex != -1;
    }

    public int getMacroIndex() {
        return macroIndex;
    }

    public boolean hasUnknownSpecial() {
        return !unknownSpecialIndexes.isEmpty();
    }

    public List<Integer> getUnknownSpecialIndexes() {
        return Collections.unmodifiableList(unknownSpecialIndexes);
    }

    public OptionalInt getSpecialKindIndex(PlanePyramidSource.SpecialImageKind kind) {
        Objects.requireNonNull(kind, "Null image kind");
        Integer ifdIndex = null;
        switch (kind) {
            case LABEL_ONLY_IMAGE -> {
                if (labelIndex != -1) {
                    ifdIndex = labelIndex;
                }
            }
            case WHOLE_SLIDE -> {
                if (macroIndex != -1) {
                    ifdIndex = macroIndex;
                }
            }
            case THUMBNAIL_IMAGE -> {
                if (thumbnailIndex != -1) {
                    ifdIndex = thumbnailIndex;
                }
            }

            // In current version the following cases are impossible,
            // because we always try to recognize strange small images as Label or as Macro
            case CUSTOM_KIND_1 -> {
                if (unknownSpecialIndexes.size() >= 1) {
                    ifdIndex = unknownSpecialIndexes.get(0);
                }
            }
            case CUSTOM_KIND_2 -> {
                if (unknownSpecialIndexes.size() >= 2) {
                    ifdIndex = unknownSpecialIndexes.get(1);
                }
            }
            case CUSTOM_KIND_3 -> {
                if (unknownSpecialIndexes.size() >= 3) {
                    ifdIndex = unknownSpecialIndexes.get(2);
                }
            }
            case CUSTOM_KIND_4 -> {
                if (unknownSpecialIndexes.size() >= 4) {
                    ifdIndex = unknownSpecialIndexes.get(3);
                }
            }
            case CUSTOM_KIND_5 -> {
                if (unknownSpecialIndexes.size() >= 5) {
                    ifdIndex = unknownSpecialIndexes.get(4);
                }
            }
        }
        return ifdIndex == null ? OptionalInt.empty() : OptionalInt.of(ifdIndex);
    }

    public boolean isSpecialMatrixSupported(PlanePyramidSource.SpecialImageKind kind) {
        return switch (kind) {
            case LABEL_ONLY_IMAGE -> labelIndex != -1;
            case WHOLE_SLIDE -> macroIndex != -1;
            case THUMBNAIL_IMAGE -> thumbnailIndex != -1;
            case CUSTOM_KIND_1 -> unknownSpecialIndexes.size() >= 1;
            case CUSTOM_KIND_2 -> unknownSpecialIndexes.size() >= 2;
            case CUSTOM_KIND_3 -> unknownSpecialIndexes.size() >= 3;
            case CUSTOM_KIND_4 -> unknownSpecialIndexes.size() >= 4;
            case CUSTOM_KIND_5 -> unknownSpecialIndexes.size() >= 5;
            default -> false;
        };
    }

    @Override
    public String toString() {
        return "special image positions among " + ifdCount + " total images: "
                + "thumbnail " + (thumbnailIndex == -1 ? "NOT FOUND" : "at " + thumbnailIndex)
                + ", label " + (labelIndex == -1 ? "NOT FOUND" : "at " + labelIndex)
                + ", macro " + (macroIndex == -1 ? "NOT FOUND" : "at " + macroIndex)
                + ", unknown " + unknownSpecialIndexes;
    }

    private void detectThumbnail() throws TiffException {
        if (ifdCount <= THUMBNAIL_IFD_INDEX) {
            return;
        }
        final TiffIFD ifd = maps.get(THUMBNAIL_IFD_INDEX).ifd();
        if (isSmallImage(ifd)) {
            this.thumbnailIndex = THUMBNAIL_IFD_INDEX;
        }
    }

    private boolean detectTwoLastImages() throws TiffException {
        if (ifdCount <= THUMBNAIL_IFD_INDEX + 2) {
            return false;
        }
        final int index1 = ifdCount - 2;
        final int index2 = ifdCount - 1;
        final TiffMap map1 = maps.get(index1);
        final TiffMap map2 = maps.get(index2);
        final TiffIFD ifd1 = map1.ifd();
        final TiffIFD ifd2 = map2.ifd();
        if (!(isSmallImage(ifd1) && isSmallImage(ifd2))) {
            return false;
        }
        LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                "  Checking last 2 small IFDs #%d %s and #%d %s for Label and Macro...",
                index1, sizesToString(map1), index2, sizesToString(map2)));
        if (ALWAYS_USE_SVS_SPECIFICATION_FOR_LABEL_AND_MACRO) {
            final int compression1 = ifd1.getCompressionCode();
            final int compression2 = ifd2.getCompressionCode();
            boolean found = false;
            if (compression1 == TagCompression.LZW.code() && compression2 == TagCompression.JPEG.code()) {
                this.labelIndex = index1;
                this.macroIndex = index2;
                found = true;
            }
            if (compression1 == TagCompression.JPEG.code() && compression2 == TagCompression.LZW.code()) {
                this.labelIndex = index2;
                this.macroIndex = index1;
                found = true;
            }
            if (found) {
                LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                        "  Label %d / Macro %d detected by SVS specification", labelIndex, macroIndex));
                return true;
            }
        }
        final double ratio1 = ratio(ifd1);
        final double ratio2 = ratio(ifd2);
        LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                "  Last 2 IFD ratios: %.5f for %d, %.5f for %d, standard Macro %.5f",
                ratio1, index1, ratio2, index2, STANDARD_MACRO_ASPECT_RATIO));
        final double maxRatio = Math.max(ratio1, ratio2);
        if (maxRatio > STANDARD_MACRO_ASPECT_RATIO * (1.0 - ALLOWED_ASPECT_RATION_DEVIATION)
                && maxRatio < STANDARD_MACRO_ASPECT_RATIO / (1.0 - ALLOWED_ASPECT_RATION_DEVIATION)) {
            // Usually, the more extended from 2 images is Macro and the more square is Label,
            // but we use this criterion only if the more extended really looks almost as a standard Macro
            this.macroIndex = ratio1 > ratio2 ? index1 : index2;
            this.labelIndex = ratio1 > ratio2 ? index2 : index1;
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                    "  Label %d / Macro %d detected by form", labelIndex, macroIndex));
            return true;
        }
        // Both images have unexpected form, so, let's consider that Macro is the greatest from them
        final double area1 = area(ifd1);
        final double area2 = area(ifd2);
        this.macroIndex = area1 > area2 ? index1 : index2;
        this.labelIndex = area1 > area2 ? index2 : index1;
        LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                "  Label %d / Macro %d detected by area", labelIndex, macroIndex));
        return true;
    }

    private void detectSingleLastImage() throws TiffException {
        if (ifdCount <= THUMBNAIL_IFD_INDEX + 1) {
            return;
        }
        final int index = ifdCount - 1;
        final TiffMap map = maps.get(index);
        final TiffIFD ifd = map.ifd();
        if (!isSmallImage(ifd)) {
            return;
        }
        final double ratio = ratio(ifd);
        LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                "  Checking last 1 small IFDs #%d %s for Label or Macro...", index, sizesToString(map)));
        LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                "  Last IFD #%d, ratio: %.5f, standard Macro %.5f", index, ratio, STANDARD_MACRO_ASPECT_RATIO));
        if (ratio <= STANDARD_MACRO_ASPECT_RATIO * (1.0 - ALLOWED_ASPECT_RATION_DEVIATION)) {
            if (ifd.getCompressionCode() == TagCompression.JPEG.code()) {
                // strange image, but very improbable that it is Label encoded by JPEG
                // (usually Label is compressed lossless)
                this.macroIndex = index;
                LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                        "  Macro %d with strange form detected by JPEG compression", index));
            } else {
                this.labelIndex = index;
                LOG.log(System.Logger.Level.DEBUG, () -> String.format("  Label %d detected by form", index));
            }
        } else if (ratio < STANDARD_MACRO_ASPECT_RATIO / (1.0 - ALLOWED_ASPECT_RATION_DEVIATION)) {
            this.macroIndex = index;
            LOG.log(System.Logger.Level.DEBUG, () -> String.format("  Macro %d detected by form", index));
        } else {
            LOG.log(System.Logger.Level.DEBUG, () -> String.format("  Last IFD %d is UNKNOWN", index));
        }
    }

    static boolean isSmallImage(TiffIFD ifd) throws TiffException {
        final long[] tileOffsets = ifd.getLongArray(Tags.TILE_OFFSETS);
//        System.out.println(tileOffsets == null ? "null" : tileOffsets.length);
        return tileOffsets == null &&
                (long) ifd.getImageDimX() * (long) ifd.getImageDimY() < MAX_PIXEL_COUNT_IN_SPECIAL_IMAGES;
    }

    static String sizesToString(TiffMap map) {
        Objects.requireNonNull(map, "Null map");
        return map.dimX() + "x" + map.dimY();
    }

    static String compressionToString(TiffMap map) {
        Objects.requireNonNull(map, "Null map");
        return String.valueOf(map.ifd().optCompression());
    }

    private static double area(TiffIFD ifd) throws TiffException {
        final long dimX = ifd.getImageDimX();
        final long dimY = ifd.getImageDimY();
        return (double) dimX * (double) dimY;
    }

    private static double ratio(TiffIFD ifd) throws TiffException {
        final long dimX = ifd.getImageDimX();
        final long dimY = ifd.getImageDimY();
        assert dimX > 0 && dimY > 0;
        // - was checked in checkThatIfdSizesArePositiveIntegers in the SVSPlanePyramidSource constructor
        return (double) Math.max(dimX, dimY) / (double) Math.min(dimX, dimY);
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: " + SVSIFDClassifier.class.getName() + " file1.svs file2.svs ...");
            return;
        }
        for (String arg : args) {
            final Path file = Paths.get(arg);
            try (TiffReader reader = new TiffReader(file)) {
                final var detector = new SVSIFDClassifier(reader.allMaps());
                System.out.printf("%s:%n%s%n%n", file, detector);
            }
        }
    }
}
