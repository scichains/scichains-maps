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

package net.algart.matrices.io.formats.tiff.bridges.scifio;

import io.scif.FormatException;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.PhotoInterp;
import io.scif.formats.tiff.TiffCompression;
import io.scif.formats.tiff.TiffRational;
import io.scif.util.FormatTools;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffMap;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffTile;
import org.scijava.util.Bytes;

import java.util.Arrays;
import java.util.Objects;

public class TiffUnpackingTools {
    private TiffUnpackingTools() {
    }

    public static boolean repackSimpleFormats(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile);
        final DetailedIFD ifd = tile.ifd();

        int code = isAdditionalPostprocessingNecessary(ifd);
        if (code == 0) {
            return false;
        }
        if (tile.getStoredDataLength() > tile.map().tileSizeInBytes() && code == 1) {
            // - Strange situation: JPEG or some extended codec has decoded to large tile.
            // But for "simple" compressions (uncompressed, LZW, Deflate) we enable this situation:
            // it helps to create special tests.
            // Note that the further adjustNumberOfPixels throws IllegalArgumentException in this situation,
            // when its argument is false.
            throw new FormatException("Too large decoded TIFF data: " + tile.getStoredDataLength() +
                    " bytes, its is greater than one " +
                    (tile.map().isTiled() ? "tile" : "strip") + " (" + tile.map().tileSizeInBytes() + " bytes); "
                    + "probably TIFF file is corrupted or format is not properly supported");
        }
        tile.adjustNumberOfPixels(true);
        // - Note: getStoredDataLength() is unpredictable, because it is the result of decompression by a codec;
        // in particular, for JPEG compression last strip in non-tiled TIFF may be shorter or even larger
        // than a full tile.
        // If cropping boundary tiles is enabled, actual height of the last strip is reduced
        // (see readEncodedTile method), so larger data is possible (it is a minor format separately).
        // If cropping boundary tiles is disabled, larger data MAY be considered as a format error,
        // because tile sizes are the FULL sizes of tile in the grid (it is checked above independently).
        // We consider this situation as an error in a case of "complex" codecs like JPEG, JPEG-2000 etc.
        // Also note: it is better to rearrange pixels before separating (if necessary),
        // because rearranging interleaved pixels is little more simple.
        tile.separateSamplesIfNecessary();

        // Note: other 2 functions, unpackUnusualBits and decodeYCbCr, always work with interleaved
        // data and separate data themselves to a tile with correct sizes
        return true;

    }

    public static boolean unpackUnusualBits(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile);
        final DetailedIFD ifd = tile.ifd();

        if (isAdditionalPostprocessingNecessary(ifd) != 0) {
            return false;
        }
        if (ifd.isStandardYCbCrNonJpeg()) {
            return false;
        }
        if (!tile.isInterleaved()) {
            throw new IllegalArgumentException("Tile data must be interleaved for correct completing " +
                    "to decode " + tile.ifd().getCompression() + " (separated data are allowed for codecs " +
                    "like JPEG, that must fully decode data themselves, but not for this " +
                    "compression): " + tile);
        }
        assert ifd.isStandardCompression() :
                "non-standard compression not checked by isAdditionalPostprocessingNecessary";

        final int samplesPerPixel = tile.samplesPerPixel();
        final PhotoInterp photometricInterpretation = ifd.getPhotometricInterpretation();
        final long sizeX = tile.getSizeX();
        final long sizeY = tile.getSizeY();
        final int resultSamplesLength = tile.getSizeInBytes();

        final int[] bitsPerSample = ifd.getBitsPerSample();
        final int bytesPerSample = tile.bytesPerSample();
        if (bytesPerSample > 4) {
            throw new UnsupportedTiffFormatException("TIFF with compression " +
                    ifd.getCompression().getCodecName() + ", " +
                    "photometric interpretation " + photometricInterpretation.getName() +
                    " and more than 4 bytes per sample (" + Arrays.toString(bitsPerSample) + " bits) " +
                    "is not supported");
        }
        final int numberOfPixels = tile.getSizeInPixels();

        final int bps0 = bitsPerSample[0];
        final boolean noDiv8 = bps0 % 8 != 0;
        final byte[] bytes = tile.getDecodedData();

        final boolean littleEndian = ifd.isLittleEndian();

        final CustomBitBuffer bb = new CustomBitBuffer(bytes);

        final byte[] unpacked = new byte[resultSamplesLength];

        long maxValue = (1 << bps0) - 1;
//        if (photometricInterpretation == PhotoInterp.CMYK) maxValue = Integer.MAX_VALUE;
        // - Why?

        int skipBits = (int) (8 - ((sizeX * bps0 * samplesPerPixel) % 8));
        if (skipBits == 8 || ((long) bytes.length * 8 < bps0 * (samplesPerPixel * sizeX + sizeY))) {
            skipBits = 0;
        }

        // unpack pixels
        MainLoop:
        for (int i = 0; i < numberOfPixels; i++) {

            for (int channel = 0; channel < samplesPerPixel; channel++) {
                final int index = bytesPerSample * (i * samplesPerPixel + channel);
                final int outputIndex = (channel * numberOfPixels + i) * bytesPerSample;

                // unpack non-YCbCr samples
                long value = 0;

                if (noDiv8) {
                    // bits per sample is not a multiple of 8

                    if ((channel == 0 && photometricInterpretation == PhotoInterp.RGB_PALETTE) ||
                            (photometricInterpretation != PhotoInterp.CFA_ARRAY &&
                                    photometricInterpretation != PhotoInterp.RGB_PALETTE)) {
//                        System.out.println((count++) + "/" + numberOfPixels * samplesPerPixel);
                        value = bb.getBits(bps0);
                        if (value == -1) {
                            break MainLoop;
                        }
                        value &= 0xffff;
                        if ((i % sizeX) == sizeX - 1) {
                            bb.skipBits(skipBits);
                        }
                    }
                } else {
                    value = Bytes.toLong(bytes, index, bytesPerSample, littleEndian);
                }

                if (photometricInterpretation == PhotoInterp.WHITE_IS_ZERO ||
                        photometricInterpretation == PhotoInterp.CMYK) {
                    value = maxValue - value;
                }

                if (outputIndex + bytesPerSample <= unpacked.length) {
                    Bytes.unpack(value, unpacked, outputIndex, bytesPerSample, littleEndian);
                }
            }
        }
        tile.setDecodedData(unpacked);
        tile.setInterleaved(false);
        return true;
    }

    public static boolean decodeYCbCr(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile);
        final DetailedIFD ifd = tile.ifd();

        if (!ifd.isStandardYCbCrNonJpeg()) {
            return false;
        }
        if (!tile.isInterleaved()) {
            throw new IllegalArgumentException("Tile data must be interleaved for correct completing " +
                    "to decode " + tile.ifd().getCompression() + " (separated data are allowed for codecs " +
                    "like JPEG, that must fully decode data themselves, but not for this " +
                    "compression): " + tile);
        }
        byte[] bytes = tile.getDecodedData();

        final TiffMap map = tile.map();
        if (map.isPlanarSeparated()) {
            // - there is no simple way to support this exotic situation: Cb/Cr values are saved in other tiles
            throw new UnsupportedTiffFormatException("TIFF YCbCr photometric interpretation is not supported " +
                    "in planar format (separated component planes: TIFF tag PlanarConfiguration=2)");
        }

        final int bits = ifd.tryEqualBitsPerSample().orElse(-1);
        if (bits != 8) {
            throw new UnsupportedTiffFormatException("Cannot unpack YCbCr TIFF image with " +
                    Arrays.toString(ifd.getBitsPerSample()) +
                    " bits per samples: only [8, 8, 8] variant is supported");
        }

        if (map.tileSamplesPerPixel() != 3) {
            throw new FormatException("TIFF YCbCr photometric interpretation requires 3 channels, but " +
                    "there are " + map.tileSamplesPerPixel() + " channels in SamplesPerPixel TIFF tag");
        }


        final int sizeX = tile.getSizeX();
        final int sizeY = tile.getSizeY();
        final int numberOfPixels = tile.getSizeInPixels();

        final byte[] unpacked = new byte[3 * numberOfPixels];

        // unpack pixels
        // set up YCbCr-specific values
        double lumaRed = PhotoInterp.LUMA_RED;
        double lumaGreen = PhotoInterp.LUMA_GREEN;
        double lumaBlue = PhotoInterp.LUMA_BLUE;
        int[] reference = ifd.getIFDIntArray(IFD.REFERENCE_BLACK_WHITE);
        if (reference == null) {
            reference = new int[]{0, 255, 128, 255, 128, 255};
            // - original SCIFIO code used here zero-filled array, this is incorrect
        }
        final int[] subsamplingLog = ifd.getYCbCrSubsamplingLogarithms();
        final TiffRational[] coefficients = ifd.optValue(
                        IFD.Y_CB_CR_COEFFICIENTS, TiffRational[].class, true)
                .orElse(new TiffRational[0]);
        if (coefficients.length >= 3) {
            lumaRed = coefficients[0].doubleValue();
            lumaGreen = coefficients[1].doubleValue();
            lumaBlue = coefficients[2].doubleValue();
        }
        final double crCoefficient = 2 - 2 * lumaRed;
        final double cbCoefficient = 2 - 2 * lumaBlue;
        final double lumaGreenInv = 1.0 / lumaGreen;
        final int subXLog = subsamplingLog[0];
        final int subYLog = subsamplingLog[1];
        final int subXMinus1 = (1 << subXLog) - 1;
        final int blockLog = subXLog + subYLog;
        final int block = 1 << blockLog;
        final int numberOfXBlocks = (sizeX + subXMinus1) >>> subXLog;
        for (int yIndex = 0, i = 0; yIndex < sizeY; yIndex++) {
            final int yBlockIndex = yIndex >>> subYLog;
            final int yAligned = yBlockIndex << subYLog;
            final int lineAligned = yBlockIndex * numberOfXBlocks;
            for (int xIndex = 0; xIndex < sizeX; xIndex++, i++) {
                // unpack non-YCbCr samples
                // unpack YCbCr unpacked; these need special handling, as
                // each of the RGB components depends upon two or more of the YCbCr
                // components
                final int blockIndex = i >>> blockLog; // = i / block
                final int aligned = blockIndex << blockLog;
                final int indexInBlock = i - aligned;
                assert indexInBlock < block;
                final int blockIndexTwice = 2 * blockIndex; // 1 block for Cb + 1 block for Cr
                final int blockStart = aligned + blockIndexTwice;
                final int lumaIndex = blockStart + indexInBlock;
                final int chromaIndex = blockStart + block;
                // Every block contains block Luma (Y) values, 1 Cb value and 1 Cr value, for example (2x2):
                //    YYYYbrYYYYbrYYYYbr...
                //                |"blockStart" position

                if (chromaIndex + 1 >= bytes.length) {
                    break;
                }

                final int yIndexInBlock = indexInBlock >>> subXLog;
                final int xIndexInBlock = indexInBlock - (yIndexInBlock << subXLog);
                final int resultYIndex = yAligned + yIndexInBlock;
                final int resultXIndex = ((blockIndex - lineAligned) << subXLog) + xIndexInBlock;
                if (TiffReader.THOROUGHLY_TEST_Y_CB_CR_LOOP) {
                    final int subX = 1 << subXLog;
                    final int subY = 1 << subYLog;
                    final int t = i / block;
                    final int pixel = i % block;
                    final long r = (long) subY * (t / numberOfXBlocks) + (pixel / subX);
                    final long c = (long) subX * (t % numberOfXBlocks) + (pixel % subX);
                    // - these formulas were used in original SCIFIO code
                    assert t / numberOfXBlocks == yBlockIndex;
                    assert t % numberOfXBlocks == blockIndex - lineAligned;
                    assert c == resultXIndex;
                    assert r == resultYIndex;
                }
                if (resultXIndex >= sizeX || resultYIndex >= sizeY) {
                    // - for a case when the sizes are not aligned by subX/subY
                    continue;
                }

                final int resultIndex = resultYIndex * sizeX + resultXIndex;
                final int y = (bytes[lumaIndex] & 0xff) - reference[0];
                final int cb = (bytes[chromaIndex] & 0xff) - reference[2];
                final int cr = (bytes[chromaIndex + 1] & 0xff) - reference[4];

                final double red = cr * crCoefficient + y;
                final double blue = cb * cbCoefficient + y;
                final double green = (y - lumaBlue * blue - lumaRed * red) * lumaGreenInv;

                unpacked[resultIndex] = (byte) toUnsignedByte(red);
                unpacked[numberOfPixels + resultIndex] = (byte) toUnsignedByte(green);
                unpacked[2 * numberOfPixels + resultIndex] = (byte) toUnsignedByte(blue);
            }
            while ((i & subXMinus1) != 0) {
                i++;
                // - horizontal alignment
            }
        }

        tile.setDecodedData(unpacked);
        tile.setInterleaved(false);
        return true;
    }

    // Note: we prefer to pass numberOfChannels directly, not calculate it on the base of IFD,
    // because in some cases (like processing tile) number of channels should be set to 1 for planar IFD,
    // but in other cases (like processing whole image) it is not so.
    public static byte[] unpackUnusualPrecisions(
            final byte[] samples,
            final IFD ifd,
            final int numberOfChannels,
            final int numberOfPixels) throws FormatException {
        Objects.requireNonNull(samples, "Null samples");
        Objects.requireNonNull(ifd, "Null IFD");
        if (numberOfChannels <= 0) {
            throw new IllegalArgumentException("Zero or negative numberOfChannels = " + numberOfChannels);
        }
        if (numberOfPixels < 0) {
            throw new IllegalArgumentException("Negative numberOfPixels = " + numberOfPixels);
        }
        final int pixelType = ifd.getPixelType();
        if (pixelType != FormatTools.FLOAT) {
            return samples;
        }
        final int[] bitsPerSample = ifd.getBitsPerSample();
        final boolean equalBitsPerSample = Arrays.stream(bitsPerSample).allMatch(t -> t == bitsPerSample[0]);
        final boolean float16 = equalBitsPerSample && bitsPerSample[0] == 16;
        final boolean float24 = equalBitsPerSample && bitsPerSample[0] == 24;
        if (!float16 && !float24) {
            return samples;
        }
        // Following code is necessary in a very rare case, and no sense to seriously optimize it
        final boolean littleEndian = ifd.isLittleEndian();
        final int packedBytesPerSample = float16 ? 2 : 3;

        final int size = TiffTools.checkedMul(new long[]{numberOfPixels, numberOfChannels, 4},
                new String[]{"number of pixels", "number of channels", "4 bytes per float"},
                () -> "Invalid sizes: ", () -> "");
        final int numberOfSamples = numberOfChannels * numberOfPixels;
        if (samples.length < numberOfSamples * packedBytesPerSample) {
            throw new IllegalArgumentException("Too short samples array byte[" + samples.length +
                    "]: it does not contain " + numberOfPixels + " pixels per " + numberOfChannels +
                    " samples, " + packedBytesPerSample + " bytes/sample");
        }
        final byte[] unpacked = new byte[size];
        final int mantissaBits = float16 ? 10 : 16;
        final int exponentBits = float16 ? 5 : 7;
        final int exponentIncrement = 127 - (pow2(exponentBits - 1) - 1);
        final int power2ExponentBitsMinus1 = pow2(exponentBits) - 1;
        final int power2MantissaBits = pow2(mantissaBits);
        final int power2MantissaBitsMinus1 = pow2(mantissaBits) - 1;
        final int bits = (packedBytesPerSample * 8) - 1;
        for (int i = 0, disp = 0; i < numberOfSamples; i++, disp += packedBytesPerSample) {
            final int v = Bytes.toInt(samples, disp, packedBytesPerSample, littleEndian);
            final int sign = v >> bits;
            int exponent = (v >> mantissaBits) & power2ExponentBitsMinus1;
            int mantissa = v & power2MantissaBitsMinus1;

            if (exponent == 0) {
                if (mantissa != 0) {
                    while (true) {
                        if (!((mantissa & power2MantissaBits) == 0)) {
                            break;
                        }
                        mantissa <<= 1;
                        exponent--;
                    }
                    exponent++;
                    mantissa &= power2MantissaBitsMinus1;
                    exponent += exponentIncrement;
                }
            } else if (exponent == power2ExponentBitsMinus1) {
                exponent = 255;
            } else {
                exponent += exponentIncrement;
            }

            mantissa <<= (23 - mantissaBits);

            final int value = (sign << 31) | (exponent << 23) | mantissa;
            Bytes.unpack(value, unpacked, i * 4, 4, littleEndian);
        }
        return unpacked;
    }

    private static int isAdditionalPostprocessingNecessary(DetailedIFD ifd) throws FormatException {
        TiffCompression compression = ifd.getCompression();
        if (!DetailedIFD.isStandard(compression) || DetailedIFD.isJpeg(compression)) {
            // - JPEG codec and all non-standard codecs like JPEG2000 should perform all necessary
            // bits unpacking or color space corrections themselves
            return 1;
        }
        if (ifd.getPhotometricInterpretation() == PhotoInterp.Y_CB_CR) {
            return 0;
        }
        return ifd.isOrdinaryPrecision() && !ifd.isStandardInvertedCompression() ? 2 : 0;
    }

    private static int toUnsignedByte(double v) {
        return v < 0.0 ? 0 : v > 255.0 ? 255 : (int) Math.round(v);
    }

    private static int pow2(int b) {
        return 1 << b;
    }
}
