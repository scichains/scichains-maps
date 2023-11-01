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
import io.scif.formats.tiff.*;
import io.scif.util.FormatTools;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffMap;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffTile;
import org.scijava.io.handle.BytesHandle;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.FileHandle;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;
import org.scijava.util.Bytes;

import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Utility methods for working with TIFF files.
 *
 * @author Daniel Alievsky
 */
public class TiffTools {
    static final boolean BUILT_IN_TIMING = getBooleanProperty(
            "net.algart.matrices.io.formats.tiff.timing");

    /**
     * Bit order mapping for reversed fill order.
     */
    private static final byte[] REVERSE = {0x00, -0x80, 0x40, -0x40, 0x20, -0x60,
            0x60, -0x20, 0x10, -0x70, 0x50, -0x30, 0x30, -0x50, 0x70, -0x10, 0x08,
            -0x78, 0x48, -0x38, 0x28, -0x58, 0x68, -0x18, 0x18, -0x68, 0x58, -0x28,
            0x38, -0x48, 0x78, -0x08, 0x04, -0x7c, 0x44, -0x3c, 0x24, -0x5c, 0x64,
            -0x1c, 0x14, -0x6c, 0x54, -0x2c, 0x34, -0x4c, 0x74, -0x0c, 0x0c, -0x74,
            0x4c, -0x34, 0x2c, -0x54, 0x6c, -0x14, 0x1c, -0x64, 0x5c, -0x24, 0x3c,
            -0x44, 0x7c, -0x04, 0x02, -0x7e, 0x42, -0x3e, 0x22, -0x5e, 0x62, -0x1e,
            0x12, -0x6e, 0x52, -0x2e, 0x32, -0x4e, 0x72, -0x0e, 0x0a, -0x76, 0x4a,
            -0x36, 0x2a, -0x56, 0x6a, -0x16, 0x1a, -0x66, 0x5a, -0x26, 0x3a, -0x46,
            0x7a, -0x06, 0x06, -0x7a, 0x46, -0x3a, 0x26, -0x5a, 0x66, -0x1a, 0x16,
            -0x6a, 0x56, -0x2a, 0x36, -0x4a, 0x76, -0x0a, 0x0e, -0x72, 0x4e, -0x32,
            0x2e, -0x52, 0x6e, -0x12, 0x1e, -0x62, 0x5e, -0x22, 0x3e, -0x42, 0x7e,
            -0x02, 0x01, -0x7f, 0x41, -0x3f, 0x21, -0x5f, 0x61, -0x1f, 0x11, -0x6f,
            0x51, -0x2f, 0x31, -0x4f, 0x71, -0x0f, 0x09, -0x77, 0x49, -0x37, 0x29,
            -0x57, 0x69, -0x17, 0x19, -0x67, 0x59, -0x27, 0x39, -0x47, 0x79, -0x07,
            0x05, -0x7b, 0x45, -0x3b, 0x25, -0x5b, 0x65, -0x1b, 0x15, -0x6b, 0x55,
            -0x2b, 0x35, -0x4b, 0x75, -0x0b, 0x0d, -0x73, 0x4d, -0x33, 0x2d, -0x53,
            0x6d, -0x13, 0x1d, -0x63, 0x5d, -0x23, 0x3d, -0x43, 0x7d, -0x03, 0x03,
            -0x7d, 0x43, -0x3d, 0x23, -0x5d, 0x63, -0x1d, 0x13, -0x6d, 0x53, -0x2d,
            0x33, -0x4d, 0x73, -0x0d, 0x0b, -0x75, 0x4b, -0x35, 0x2b, -0x55, 0x6b,
            -0x15, 0x1b, -0x65, 0x5b, -0x25, 0x3b, -0x45, 0x7b, -0x05, 0x07, -0x79,
            0x47, -0x39, 0x27, -0x59, 0x67, -0x19, 0x17, -0x69, 0x57, -0x29, 0x37,
            -0x49, 0x77, -0x09, 0x0f, -0x71, 0x4f, -0x31, 0x2f, -0x51, 0x6f, -0x11,
            0x1f, -0x61, 0x5f, -0x21, 0x3f, -0x41, 0x7f, -0x01};


    private TiffTools() {
    }

    public static Class<?> pixelTypeToElementType(int pixelType) {
        return switch (pixelType) {
            case FormatTools.INT8, FormatTools.UINT8 -> byte.class;
            case FormatTools.INT16, FormatTools.UINT16 -> short.class;
            case FormatTools.INT32, FormatTools.UINT32 -> int.class;
            case FormatTools.FLOAT -> float.class;
            case FormatTools.DOUBLE -> double.class;
            default -> throw new IllegalArgumentException("Unknown pixel type: " + pixelType);
        };
    }

    public static int arrayToPixelType(Object javaArray, boolean signedIntegers) {
        Objects.requireNonNull(javaArray, "Null Java array");
        Class<?> elementType = javaArray.getClass().getComponentType();
        if (elementType == null) {
            throw new IllegalArgumentException("The specified javaArray is not actual an array: " +
                    "it is " + javaArray.getClass());
        }
        return elementTypeToPixelType(elementType, signedIntegers);
    }

    public static int elementTypeToPixelType(Class<?> elementType, boolean signedIntegers) {
        Objects.requireNonNull(elementType, "Null elementType");
        if (elementType == byte.class) {
            return signedIntegers ? FormatTools.INT8 : FormatTools.UINT8;
        } else if (elementType == short.class) {
            return signedIntegers ? FormatTools.INT16 : FormatTools.UINT16;
        } else if (elementType == int.class) {
            return signedIntegers ? FormatTools.INT32 : FormatTools.UINT32;
        } else if (elementType == float.class) {
            return FormatTools.FLOAT;
        } else if (elementType == double.class) {
            return FormatTools.DOUBLE;
        } else {
            throw new IllegalArgumentException("Element type " + elementType +
                    " is unsupported: it cannot be converted to pixel type");
        }
    }

    public static Object bytesToArray(byte[] bytes, int pixelType, boolean littleEndian) {
        Objects.requireNonNull(bytes, "Null bytes");
        switch (pixelType) {
            case FormatTools.INT8, FormatTools.UINT8 -> {
                return bytes;
            }
            case FormatTools.INT16, FormatTools.UINT16 -> {
                return bytesToShortArray(bytes, littleEndian);
            }
            case FormatTools.INT32, FormatTools.UINT32 -> {
                return bytesToIntArray(bytes, littleEndian);
            }
            case FormatTools.FLOAT -> {
                return bytesToFloatArray(bytes, littleEndian);
            }
            case FormatTools.DOUBLE -> {
                return bytesToDoubleArray(bytes, littleEndian);
            }
        }
        throw new IllegalArgumentException("Unknown pixel type: " + pixelType);
    }

    public static short[] bytesToShortArray(byte[] samples, boolean littleEndian) {
        final short[] shortValues = new short[samples.length / 2];
        final ByteBuffer bb = ByteBuffer.wrap(samples);
        bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        bb.asShortBuffer().get(shortValues);
        return shortValues;
    }

    public static int[] bytesToIntArray(byte[] samples, boolean littleEndian) {
        final int[] intValues = new int[samples.length / 4];
        final ByteBuffer bb = ByteBuffer.wrap(samples);
        bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        bb.asIntBuffer().get(intValues);
        return intValues;
    }

    public static long[] bytesToLongArray(byte[] samples, boolean littleEndian) {
        final long[] longValues = new long[samples.length / 8];
        final ByteBuffer bb = ByteBuffer.wrap(samples);
        bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        bb.asLongBuffer().get(longValues);
        return longValues;
    }

    public static float[] bytesToFloatArray(byte[] samples, boolean littleEndian) {
        final float[] floatValues = new float[samples.length / 4];
        final ByteBuffer bb = ByteBuffer.wrap(samples);
        bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        bb.asFloatBuffer().get(floatValues);
        return floatValues;
    }

    public static double[] bytesToDoubleArray(byte[] samples, boolean littleEndian) {
        final double[] doubleValues = new double[samples.length / 8];
        final ByteBuffer bb = ByteBuffer.wrap(samples);
        bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        bb.asDoubleBuffer().get(doubleValues);
        return doubleValues;
    }

    public static byte[] arrayToBytes(Object javaArray, boolean littleEndian) {
        int pixelType = arrayToPixelType(javaArray, false);
        // - note: signed and unsigned values correspond to the same element types
        switch (pixelType) {
            case FormatTools.INT8, FormatTools.UINT8 -> {
                return (byte[]) javaArray;
            }
            case FormatTools.INT16, FormatTools.UINT16 -> {
                final short[] shortValues = (short[]) javaArray;
                final byte[] v = new byte[shortValues.length * 2];
                final ByteBuffer bb = ByteBuffer.wrap(v);
                bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                bb.asShortBuffer().put(shortValues);
                return v;
            }
            case FormatTools.INT32, FormatTools.UINT32 -> {
                final int[] intValues = (int[]) javaArray;
                final byte[] v = new byte[intValues.length * 4];
                final ByteBuffer bb = ByteBuffer.wrap(v);
                bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                bb.asIntBuffer().put(intValues);
                return v;
            }
            case FormatTools.FLOAT -> {
                final float[] floatValues = (float[]) javaArray;
                final byte[] v = new byte[floatValues.length * 4];
                final ByteBuffer bb = ByteBuffer.wrap(v);
                bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                bb.asFloatBuffer().put(floatValues);
                return v;
            }
            case FormatTools.DOUBLE -> {
                final double[] doubleValue = (double[]) javaArray;
                final byte[] v = new byte[doubleValue.length * 8];
                final ByteBuffer bb = ByteBuffer.wrap(v);
                bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                bb.asDoubleBuffer().put(doubleValue);
                return v;
            }
        }
        throw new AssertionError("(should be already checked in arrayToPixelType)");
    }

    public static byte[] toInterleavedSamples(
            final byte[] bytes,
            final int numberOfChannels,
            final int bytesPerSample,
            final int numberOfPixels) {
        Objects.requireNonNull(bytes, "Null bytes");
        if (numberOfChannels <= 0) {
            throw new IllegalArgumentException("Zero or negative numberOfChannels = " + numberOfChannels);
        }
        if (bytesPerSample <= 0) {
            throw new IllegalArgumentException("Zero or negative bytesPerSample = " + bytesPerSample);
        }
        if (numberOfPixels < 0) {
            throw new IllegalArgumentException("Negative numberOfPixels = " + numberOfPixels);
        }
        final int size = checkedMulNoException(
                new long[]{numberOfPixels, numberOfChannels, bytesPerSample},
                new String[]{"number of pixels", "bytes per pixel", "bytes per sample"},
                () -> "Invalid sizes: ", () -> "");
        // - exception usually should not occur: this function is typically called after analysing IFD
        if (bytes.length < size) {
            throw new IllegalArgumentException("Too short bytes array: " + bytes.length + " < " + size);
        }
        if (numberOfChannels == 1) {
            return bytes;
        }
        final int bandSize = numberOfPixels * bytesPerSample;
        final byte[] interleavedBytes = new byte[size];
        if (bytesPerSample == 1) {
            // - optimization
            if (numberOfChannels == 3) {
                // - most typical case
                quickInterleave3(interleavedBytes, bytes, bandSize);
            } else {
                for (int i = 0, disp = 0; i < bandSize; i++) {
                    for (int bandDisp = i, j = 0; j < numberOfChannels; j++, bandDisp += bandSize) {
                        // note: we must check j, not bandDisp, because "bandDisp += bandSize" can lead to overflow
                        interleavedBytes[disp++] = bytes[bandDisp];
                    }
                }
            }
        } else {
            for (int i = 0, disp = 0; i < bandSize; i += bytesPerSample) {
                for (int bandDisp = i, j = 0; j < numberOfChannels; j++, bandDisp += bandSize) {
                    for (int k = 0; k < bytesPerSample; k++) {
                        interleavedBytes[disp++] = bytes[bandDisp + k];
                    }
                }
            }
        }
        return interleavedBytes;
    }

    public static byte[] toSeparatedSamples(
            final byte[] bytes,
            final int numberOfChannels,
            final int bytesPerSample,
            final int numberOfPixels) {
        Objects.requireNonNull(bytes, "Null bytes");
        if (numberOfChannels <= 0) {
            throw new IllegalArgumentException("Zero or negative numberOfChannels = " + numberOfChannels);
        }
        if (bytesPerSample <= 0) {
            throw new IllegalArgumentException("Zero or negative bytesPerSample = " + bytesPerSample);
        }
        if (numberOfPixels < 0) {
            throw new IllegalArgumentException("Negative numberOfPixels = " + numberOfPixels);
        }
        final int size = checkedMulNoException(
                new long[]{numberOfPixels, numberOfChannels, bytesPerSample},
                new String[]{"number of pixels", "bytes per pixel", "bytes per sample"},
                () -> "Invalid sizes: ", () -> "");
        // - exception usually should not occur: this function is typically called after analysing IFD
        if (bytes.length < size) {
            throw new IllegalArgumentException("Too short bytes array: " + bytes.length + " < " + size);
        }
        if (numberOfChannels == 1) {
            return bytes;
        }
        final int bandSize = numberOfPixels * bytesPerSample;
        final byte[] separatedBytes = new byte[size];
        if (bytesPerSample == 1) {
            // - optimization
            if (numberOfChannels == 3) {
                // - most typical case
                quickSeparate3(separatedBytes, bytes, bandSize);
            } else {
                for (int i = 0, disp = 0; i < bandSize; i++) {
                    for (int bandDisp = i, j = 0; j < numberOfChannels; j++, bandDisp += bandSize) {
                        // note: we must check j, not bandDisp, because "bandDisp += bandSize" can lead to overflow
                        separatedBytes[bandDisp] = bytes[disp++];
                    }
                }
            }
        } else {
            for (int i = 0, disp = 0; i < bandSize; i += bytesPerSample) {
                for (int bandDisp = i, j = 0; j < numberOfChannels; j++, bandDisp += bandSize) {
                    for (int k = 0; k < bytesPerSample; k++) {
                        separatedBytes[bandDisp + k] = bytes[disp++];
                    }
                }
            }
        }
        return separatedBytes;
    }

    public static void invertFillOrderIfRequested(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile, "Null tile");
        invertFillOrderIfRequested(tile.ifd(), tile.getData());
    }

    /**
     * Changes bits order inside the passed array if FillOrder=2.
     * Note: GIMP and most viewers also do not support this feature.
     *
     * @param ifd   IFD
     * @param bytes bytes
     * @throws FormatException in a case of error in IFD
     */
    public static void invertFillOrderIfRequested(final IFD ifd, final byte[] bytes) throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        if (ifd.getFillOrder() == FillOrder.REVERSED) {
            // swap bits order of all bytes
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = REVERSE[bytes[i] & 0xFF];
            }
        }
    }

    // Clone of the function DefaultTiffService.undifference
    // The main reason to make this clone is removing usage of LogService class
    // and to fix a bug: it was not work with tiles, only with strips.
    public static void subtractPredictionIfRequested(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile, "Null tile");
        final DetailedIFD ifd = tile.ifd();
        final byte[] data = tile.getDecodedData();
        final int predictor = ifd.getIFDIntValue(IFD.PREDICTOR, DetailedIFD.PREDICTOR_NONE);
        if (predictor == DetailedIFD.PREDICTOR_HORIZONTAL) {
            final int[] bitsPerSample = ifd.getBitsPerSample();
            final boolean little = ifd.isLittleEndian();
            final int bytes = ifd.getBytesPerSample()[0];
            final int len = bytes * (ifd.isPlanarSeparated() ? 1 : bitsPerSample.length);
            final long xSize = tile.getSizeX();
            final long xSizeInBytes = xSize * len;

            int k = data.length - bytes;
            long xOffset = k % xSizeInBytes;

            if (bytes == 1) {
                for (; k >= 0; k--, xOffset--) {
                    if (xOffset < 0) {
                        xOffset += xSizeInBytes;
                    }
//                    assert (k / len % xSize == 0) == (xOffset < len);
                    if (xOffset >= len) {
                        data[k] -= data[k - len];
                    }
                }
            } else {
                for (; k >= 0; k -= bytes) {
                    if (k / len % xSize == 0) {
                        continue;
                    }
                    int value = Bytes.toInt(data, k, bytes, little);
                    value -= Bytes.toInt(data, k - len, bytes, little);
                    Bytes.unpack(value, data, k, bytes, little);
                }
            }
        } else if (predictor != DetailedIFD.PREDICTOR_NONE) {
            throw new FormatException("Unknown Predictor (" + predictor + ")");
        }
    }

    // Clone of the function DefaultTiffService.difference
    // The main reason to make this clone is removing usage of LogService class
    // and to fix a bug: it was not work with tiles, only with strips.
    public static void addPredictionIfRequested(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile, "Null tile");
        final DetailedIFD ifd = tile.ifd();
        final byte[] data = tile.getDecodedData();
        final int predictor = ifd.getIFDIntValue(IFD.PREDICTOR, DetailedIFD.PREDICTOR_NONE);
        if (predictor == DetailedIFD.PREDICTOR_HORIZONTAL) {
            final int[] bitsPerSample = ifd.getBitsPerSample();
            final boolean little = ifd.isLittleEndian();

            final int bytes = ifd.getBytesPerSample()[0];
            final int len = bytes * (ifd.isPlanarSeparated() ? 1 : bitsPerSample.length);
            final long xSize = tile.getSizeX();
            final long xSizeInBytes = xSize * len;

            int k = 0;
            long xOffset = 0;

            if (bytes == 1) {
                for (; k <= data.length - 1; k++, xOffset++) {
                    if (xOffset == xSizeInBytes) {
                        xOffset = 0;
                    }
//                    assert (k / len % xSize == 0) == (xOffset < len);
                    if (xOffset >= len) {
                        data[k] += data[k - len];
                    }
                }
            } else {
                for (; k <= data.length - bytes; k += bytes) {
                    if (k / len % xSize == 0) {
                        continue;
                    }
                    int value = Bytes.toInt(data, k, bytes, little);
                    value += Bytes.toInt(data, k - len, bytes, little);
                    Bytes.unpack(value, data, k, bytes, little);
                }
            }
        } else if (predictor != DetailedIFD.PREDICTOR_NONE) {
            throw new FormatException("Unknown Predictor (" + predictor + ")");
        }
    }

    public static boolean rearrangeUnpackedSamples(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile);
        final DetailedIFD ifd = tile.ifd();

        int code = isAdditionalRepackNecessary(ifd);
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

    public static boolean separateYCbCrToRGB(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile);
        final DetailedIFD ifd = tile.ifd();

        if (!ifd.isStandardYCbCrNonJpeg()) {
            return false;
        }
        checkInterleaved(tile);
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
        final TiffRational[] coefficients = ifd.getValue(
                        IFD.Y_CB_CR_COEFFICIENTS, TiffRational[].class, true)
                .orElse(new TiffRational[0]);
        if (coefficients.length >= 3) {
            lumaRed = coefficients[0].doubleValue();
            lumaGreen = coefficients[1].doubleValue();
            lumaBlue = coefficients[2].doubleValue();
        }
        final double crCoefficient = 2.0 - 2.0 * lumaRed;
        final double cbCoefficient = 2.0 - 2.0 * lumaBlue;
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

    public static boolean separateBitsAndInvertValues(TiffTile tile, boolean suppressValueCorrection)
            throws FormatException {
        Objects.requireNonNull(tile);
        final DetailedIFD ifd = tile.ifd();

        if (isAdditionalRepackNecessary(ifd) != 0) {
            return false;
        }
        if (ifd.isStandardYCbCrNonJpeg()) {
            return false;
        }
        checkInterleaved(tile);
        assert ifd.isStandardCompression() && !ifd.isJpeg() :
                "non-standard/JPEG compression not checked by isAdditionalRepackNecessary";

        final int samplesPerPixel = tile.samplesPerPixel();
        final PhotoInterp photometricInterpretation = ifd.getPhotometricInterpretation();
        final long sizeX = tile.getSizeX();
        final long sizeY = tile.getSizeY();
        final int resultSamplesLength = tile.getSizeInBytes();

        final int[] bitsPerSample = ifd.getBitsPerSample();
        assert samplesPerPixel <= bitsPerSample.length :
                "samplesPerPixel=" + samplesPerPixel + ">bitsPerSample.length, " +
                        "but it is possible only for OLD_JPEG, that should be already checked";
        // - but samplesPerPixel can be =1 for planar-separated tiles
        final int bytesPerSample = tile.bytesPerSample();
        if (bytesPerSample > 4) {
            throw new UnsupportedTiffFormatException("Not supported TIFF format: compression \"" +
                    ifd.getCompression().getCodecName() + "\", " +
                    "photometric interpretation \"" + photometricInterpretation.getName() +
                    "\" and " + bytesPerSample + " bytes per sample (" +
                    Arrays.toString(bitsPerSample) + " bits)");
        }
        // So, the only non-standard byte count is 3;
        // 24-bit float and 17..24-bit integer cases will be processed later in unpackUnusualPrecisions

        final int numberOfPixels = tile.getSizeInPixels();
        final boolean byteAligned = Arrays.stream(bitsPerSample).noneMatch(bits -> (bits & 7) != 0);
        final int alignedBitsPerSample = 8 * bytesPerSample;

        final int bps0 = bitsPerSample[0];
        final byte[] bytes = tile.getDecodedData();

        final boolean littleEndian = ifd.isLittleEndian();

        final BitsUnpacker bb = BitsUnpacker.getUnpackerHighBitFirst(bytes);

        final byte[] unpacked = new byte[resultSamplesLength];

        final long maxValue = (1 << bps0) - 1;

        int skipBits = (int) (8 - ((sizeX * bps0 * samplesPerPixel) % 8));
        if (skipBits == 8 || ((long) bytes.length * 8 < bps0 * (samplesPerPixel * sizeX + sizeY))) {
            skipBits = 0;
        }
        final int[] multipliers = new int[bitsPerSample.length];
        for (int k = 0; k < multipliers.length; k++) {
            multipliers[k] = ((1 << 8 * bytesPerSample) - 1) / ((1 << bitsPerSample[k]) - 1);
            // - note that 2^n-1 is divisible by 2^m-1 when m < n
        }

        if (photometricInterpretation == PhotoInterp.RGB_PALETTE ||
                photometricInterpretation == PhotoInterp.CFA_ARRAY ||
                photometricInterpretation == PhotoInterp.TRANSPARENCY_MASK) {
            suppressValueCorrection = true;
        }
        // unpack pixels
        MainLoop:
        for (int i = 0; i < numberOfPixels; i++) {

            for (int channel = 0; channel < samplesPerPixel; channel++) {
                final int bits = bitsPerSample[channel];
                final int index = bytesPerSample * (i * samplesPerPixel + channel);
                final int outputIndex = (channel * numberOfPixels + i) * bytesPerSample;

                // unpack non-YCbCr samples
                long value = 0;

                if (!byteAligned) {
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
                    }
                } else {
                    value = Bytes.toLong(bytes, index, bytesPerSample, littleEndian);
                }

                if (!suppressValueCorrection) {
                    if (photometricInterpretation == PhotoInterp.WHITE_IS_ZERO ||
                            photometricInterpretation == PhotoInterp.CMYK) {
                        value = maxValue - value;
                    }
                    value *= multipliers[channel];
                }

                if (outputIndex + bytesPerSample <= unpacked.length) {
                    Bytes.unpack(value, unpacked, outputIndex, bytesPerSample, littleEndian);
                }
            }
            if ((i % sizeX) == sizeX - 1) {
                bb.skipBits(skipBits);
            }
        }
        tile.setDecodedData(unpacked);
        tile.setInterleaved(false);
        return true;
    }

    // Note: we prefer to pass numberOfChannels directly, not calculate it on the base of IFD,
    // because in some cases (like processing tile) number of channels should be set to 1 for planar IFD,
    // but in other cases (like processing whole image) it is not so.
    // Note: this method CHANGES the number of bytes/sample.
    public static byte[] unpackUnusualPrecisions(
            final byte[] samples,
            final DetailedIFD ifd,
            final int numberOfChannels,
            final int numberOfPixels,
            boolean suppressScalingUnsignedInt24) throws FormatException {
        Objects.requireNonNull(samples, "Null samples");
        Objects.requireNonNull(ifd, "Null IFD");
        if (numberOfChannels <= 0) {
            throw new IllegalArgumentException("Zero or negative numberOfChannels = " + numberOfChannels);
        }
        if (numberOfPixels < 0) {
            throw new IllegalArgumentException("Negative numberOfPixels = " + numberOfPixels);
        }
        final int packedBytesPerSample = ifd.equalBytesPerSample();
        final int pixelType = ifd.pixelType();
        final boolean fp = pixelType == FormatTools.FLOAT || pixelType == FormatTools.DOUBLE;
        // - actually DOUBLE is not used below
        final int bitsPerSample = ifd.tryEqualBitsPerSample().orElse(-1);
        final boolean float16 = bitsPerSample == 16 && fp;
        final boolean float24 = bitsPerSample == 24 && fp;
        final boolean int24 = packedBytesPerSample == 3 && !float24;
        // If the data were correctly loaded into a tile with usage TiffReader.completeDecoding method, then:
        // 1) they are aligned by 8-bit (by unpackBitsAndInvertValues method);
        // 2) number of bytes per sample may be only 1..4 or 8: other cases are rejected by unpackBitsAndInvertValues.
        // So, we only need to check a case 3 bytes (17..24 bits before correction) and special cases float16/float24.
        if (!float16 && !float24 && !int24) {
            return samples;
        }
        // Following code is necessary in a very rare case, and no sense to seriously optimize it
        final boolean littleEndian = ifd.isLittleEndian();

        final int size = checkedMul(new long[]{numberOfPixels, numberOfChannels, 4},
                new String[]{"number of pixels", "number of channels", "4 bytes per float"},
                () -> "Invalid sizes: ", () -> "");
        final int numberOfSamples = numberOfChannels * numberOfPixels;
        if (samples.length < numberOfSamples * packedBytesPerSample) {
            throw new IllegalArgumentException("Too short samples array byte[" + samples.length +
                    "]: it does not contain " + numberOfPixels + " pixels per " + numberOfChannels +
                    " samples, " + packedBytesPerSample + " bytes/sample");
        }
        final byte[] unpacked = new byte[size];
        if (int24) {
            for (int i = 0, disp = 0; i < numberOfSamples; i++, disp += packedBytesPerSample) {
                // - very rare case, no sense to optimize
                final int value = Bytes.toInt(samples, disp, packedBytesPerSample, littleEndian);
                final long newValue = suppressScalingUnsignedInt24 ? value : (long) value << 8;
                Bytes.unpack(newValue, unpacked, i * 4, 4, littleEndian);
            }
            return unpacked;
        }

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

    public static DataHandle<Location> getExistingFileHandle(Path file) throws FileNotFoundException {
        if (!Files.isRegularFile(file)) {
            throw new FileNotFoundException("File " + file
                    + (Files.exists(file) ? " is not a regular file" : " does not exist"));
        }
        return getFileHandle(file);
    }

    public static void checkRequestedArea(long fromX, long fromY, long sizeX, long sizeY) {
        if (fromX < 0 || fromY < 0) {
            throw new IllegalArgumentException("Negative fromX = " + fromX + " or fromY = " + fromY);
        }
        if (sizeX < 0 || sizeY < 0) {
            throw new IllegalArgumentException("Negative sizeX = " + sizeX + " or sizeY = " + sizeY);
        }
        if (sizeX > Integer.MAX_VALUE - fromX || sizeY > Integer.MAX_VALUE - fromY) {
            throw new IllegalArgumentException("Requested area [" + fromX + ".." + (fromX + sizeX - 1) +
                    " x " + fromY + ".." + (fromY + sizeY - 1) + " is out of 0..2^31-1 ranges");
        }
    }

    public static void checkRequestedAreaInArray(byte[] array, long sizeX, long sizeY, int bytesPerPixel) {
        Objects.requireNonNull(array, "Null array");
        checkRequestedAreaInArray(array.length, sizeX, sizeY, bytesPerPixel);
    }

    public static void checkRequestedAreaInArray(int arrayLength, long sizeX, long sizeY, int pixelLength) {
        if (arrayLength < 0) {
            throw new IllegalArgumentException("Negative arrayLength = " + arrayLength);
        }
        if (pixelLength <= 0) {
            throw new IllegalArgumentException("Zero or negative pixelLength = " + pixelLength);
        }
        checkRequestedArea(0, 0, sizeX, sizeY);
        if (sizeX * sizeY > arrayLength || sizeX * sizeY * (long) pixelLength > arrayLength) {
            throw new IllegalArgumentException("Requested area " + sizeX + "x" + sizeY +
                    " is too large for array of " + arrayLength + " elements, " + pixelLength + " per pixel");
        }
    }

    // Exact copy of old TiffParser.unpackBytes method
    static void unpackBytesLegacy(
            final byte[] samples, final int startIndex,
            final byte[] bytes, final IFD ifd) throws FormatException {
        final boolean planar = ifd.getPlanarConfiguration() == 2;

        final TiffCompression compression = ifd.getCompression();
        PhotoInterp photoInterp = ifd.getPhotometricInterpretation();
        if (compression == TiffCompression.JPEG) photoInterp = PhotoInterp.RGB;

        final int[] bitsPerSample = ifd.getBitsPerSample();
        int nChannels = bitsPerSample.length;

        int sampleCount = (int) (((long) 8 * bytes.length) / bitsPerSample[0]);
        //!! It is a bug! This formula is invalid in the case skipBits!=0
        if (photoInterp == PhotoInterp.Y_CB_CR) sampleCount *= 3;
        if (planar) {
            nChannels = 1;
        } else {
            sampleCount /= nChannels;
        }

//        log.trace("unpacking " + sampleCount + " samples (startIndex=" +
//                startIndex + "; totalBits=" + (nChannels * bitsPerSample[0]) +
//                "; numBytes=" + bytes.length + ")");

        final long imageWidth = ifd.getImageWidth();
        final long imageHeight = ifd.getImageLength();

        final int bps0 = bitsPerSample[0];
        final int numBytes = ifd.getBytesPerSample()[0];
        final int nSamples = samples.length / (nChannels * numBytes);

        final boolean noDiv8 = bps0 % 8 != 0;
        final boolean bps8 = bps0 == 8;
        final boolean bps16 = bps0 == 16;

        final boolean littleEndian = ifd.isLittleEndian();

        final io.scif.codec.BitBuffer bb = new io.scif.codec.BitBuffer(bytes);

        // Hyper optimisation that takes any 8-bit or 16-bit data, where there
        // is
        // only one channel, the source byte buffer's size is less than or equal
        // to
        // that of the destination buffer and for which no special unpacking is
        // required and performs a simple array copy. Over the course of reading
        // semi-large datasets this can save **billions** of method calls.
        // Wed Aug 5 19:04:59 BST 2009
        // Chris Allan <callan@glencoesoftware.com>
        if ((bps8 || bps16) && bytes.length <= samples.length && nChannels == 1 &&
                photoInterp != PhotoInterp.WHITE_IS_ZERO &&
                photoInterp != PhotoInterp.CMYK && photoInterp != PhotoInterp.Y_CB_CR) {
            System.arraycopy(bytes, 0, samples, 0, bytes.length);
            return;
        }

        long maxValue = (long) Math.pow(2, bps0) - 1;
        if (photoInterp == PhotoInterp.CMYK) maxValue = Integer.MAX_VALUE;

        int skipBits = (int) (8 - ((imageWidth * bps0 * nChannels) % 8));
        if (skipBits == 8 || (bytes.length * 8L < bps0 * (nChannels * imageWidth +
                imageHeight))) {
            skipBits = 0;
        }

        // set up YCbCr-specific values
        float lumaRed = PhotoInterp.LUMA_RED;
        float lumaGreen = PhotoInterp.LUMA_GREEN;
        float lumaBlue = PhotoInterp.LUMA_BLUE;
        int[] reference = ifd.getIFDIntArray(IFD.REFERENCE_BLACK_WHITE);
        if (reference == null) {
            reference = new int[]{0, 0, 0, 0, 0, 0};
        }
        final int[] subsampling = ifd.getIFDIntArray(IFD.Y_CB_CR_SUB_SAMPLING);
        final TiffRational[] coefficients = (TiffRational[]) ifd.getIFDValue(
                IFD.Y_CB_CR_COEFFICIENTS);
        if (coefficients != null) {
            lumaRed = coefficients[0].floatValue();
            lumaGreen = coefficients[1].floatValue();
            lumaBlue = coefficients[2].floatValue();
        }
        final int subX = subsampling == null ? 2 : subsampling[0];
        final int subY = subsampling == null ? 2 : subsampling[1];
        final int block = subX * subY;
        final int nTiles = (int) (imageWidth / subX);

        // unpack pixels
        for (int sample = 0; sample < sampleCount; sample++) {
            final int ndx = startIndex + sample;
            if (ndx >= nSamples) break;

            for (int channel = 0; channel < nChannels; channel++) {
                final int index = numBytes * (sample * nChannels + channel);
                final int outputIndex = (channel * nSamples + ndx) * numBytes;

                // unpack non-YCbCr samples
                if (photoInterp != PhotoInterp.Y_CB_CR) {
                    long value = 0;

                    if (noDiv8) {
                        // bits per sample is not a multiple of 8

                        if ((channel == 0 && photoInterp == PhotoInterp.RGB_PALETTE) ||
                                (photoInterp != PhotoInterp.CFA_ARRAY &&
                                        photoInterp != PhotoInterp.RGB_PALETTE)) {
//                            System.out.println((count++) + "/" + nSamples * nChannels);
                            value = bb.getBits(bps0) & 0xffff;
                            if ((ndx % imageWidth) == imageWidth - 1) {
                                bb.skipBits(skipBits);
                            }
                        }
                    } else {
                        value = Bytes.toLong(bytes, index, numBytes, littleEndian);
                    }

                    if (photoInterp == PhotoInterp.WHITE_IS_ZERO ||
                            photoInterp == PhotoInterp.CMYK) {
                        value = maxValue - value;
                    }

                    if (outputIndex + numBytes <= samples.length) {
                        Bytes.unpack(value, samples, outputIndex, numBytes, littleEndian);
                    }
                } else {
                    // unpack YCbCr samples; these need special handling, as
                    // each of
                    // the RGB components depends upon two or more of the YCbCr
                    // components
                    if (channel == nChannels - 1) {
                        final int lumaIndex = sample + (2 * (sample / block));
                        final int chromaIndex = (sample / block) * (block + 2) + block;

                        if (chromaIndex + 1 >= bytes.length) break;

                        final int tile = ndx / block;
                        final int pixel = ndx % block;
                        final long r = (long) subY * (tile / nTiles) + (pixel / subX);
                        final long c = (long) subX * (tile % nTiles) + (pixel % subX);

                        final int idx = (int) (r * imageWidth + c);

                        if (idx < nSamples) {
                            final int y = (bytes[lumaIndex] & 0xff) - reference[0];
                            final int cb = (bytes[chromaIndex] & 0xff) - reference[2];
                            final int cr = (bytes[chromaIndex + 1] & 0xff) - reference[4];

                            final int red = (int) (cr * (2 - 2 * lumaRed) + y);
                            final int blue = (int) (cb * (2 - 2 * lumaBlue) + y);
                            final int green = (int) ((y - lumaBlue * blue - lumaRed * red) /
                                    lumaGreen);

                            samples[idx] = (byte) (red & 0xff);
                            samples[nSamples + idx] = (byte) (green & 0xff);
                            samples[2 * nSamples + idx] = (byte) (blue & 0xff);
                        }
                    }
                }
            }
        }
    }

    static DataHandle<Location> getFileHandle(Path file) {
        Objects.requireNonNull(file, "Null file");
        return getFileHandle(new FileLocation(file.toFile()));
    }

    /**
     * Warning: you should never call {@link DataHandle#set(Object)} method of the returned result!
     * It can lead to unpredictable <tt>ClassCastException</tt>.
     */
    @SuppressWarnings("rawtypes, unchecked")
    static DataHandle<Location> getFileHandle(FileLocation fileLocation) {
        Objects.requireNonNull(fileLocation, "Null fileLocation");
        FileHandle fileHandle = new FileHandle(fileLocation);
        return (DataHandle) fileHandle;
    }

    /**
     * Warning: you should never call {@link DataHandle#set(Object)} method of the returned result!
     * It can lead to unpredictable <tt>ClassCastException</tt>.
     */
    @SuppressWarnings("rawtypes, unchecked")
    static DataHandle<Location> getBytesHandle(BytesLocation bytesLocation) {
        Objects.requireNonNull(bytesLocation, "Null bytesLocation");
        BytesHandle bytesHandle = new BytesHandle(bytesLocation);
        return (DataHandle) bytesHandle;
    }

    static int checkedMul(
            long v1, long v2, long v3,
            String n1, String n2, String n3,
            Supplier<String> prefix,
            Supplier<String> postfix) throws FormatException {
        return checkedMul(new long[]{v1, v2, v3}, new String[]{n1, n2, n3}, prefix, postfix);
    }

    static int checkedMul(
            long v1, long v2, long v3, long v4,
            String n1, String n2, String n3, String n4,
            Supplier<String> prefix,
            Supplier<String> postfix) throws FormatException {
        return checkedMul(new long[]{v1, v2, v3, v4}, new String[]{n1, n2, n3, n4}, prefix, postfix);
    }

    static int checkedMulNoException(
            long[] values,
            String[] names,
            Supplier<String> prefix,
            Supplier<String> postfix) {
        try {
            return checkedMul(values, names, prefix, postfix);
        } catch (FormatException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    static int checkedMul(
            long[] values,
            String[] names,
            Supplier<String> prefix,
            Supplier<String> postfix) throws FormatException {
        Objects.requireNonNull(values);
        Objects.requireNonNull(prefix);
        Objects.requireNonNull(postfix);
        Objects.requireNonNull(names);
        if (values.length == 0) {
            return 1;
        }
        long result = 1L;
        double product = 1.0;
        boolean overflow = false;
        for (int i = 0; i < values.length; i++) {
            long m = values[i];
            if (m < 0) {
                throw new FormatException(prefix.get() + "negative " + names[i] + " = " + m + postfix.get());
            }
            result *= m;
            product *= m;
            if (result > Integer.MAX_VALUE) {
                overflow = true;
                // - we just indicate this, but still calculate the floating-point product
            }
        }
        if (overflow) {
            throw new FormatException(prefix.get() + "too large " + String.join(" * ", names) +
                    " = " + Arrays.stream(values).mapToObj(String::valueOf).collect(
                    Collectors.joining(" * ")) +
                    " = " + product + " >= 2^31" + postfix.get());
        }
        return (int) result;
    }

    static void checkRequestedArea(long fromX, long fromY, long sizeX, long sizeY, long imageDimX, long imageDimY) {
        checkRequestedArea(fromX, fromY, sizeX, sizeY);
        if (imageDimX < 0 || imageDimY < 0) {
            throw new IllegalArgumentException("Negative imageDimX = " + imageDimX +
                    " or imageDimY = " + imageDimY);
        }
        if (fromX > imageDimX - sizeX || fromY > imageDimY - sizeY) {
            throw new IllegalArgumentException("Requested area [" + fromX + ".." + (fromX + sizeX - 1) +
                    " x " + fromY + ".." + (fromY + sizeY - 1) + "] is out of image ranges " +
                    imageDimX + "x" + imageDimY);
        }
    }

    static boolean getBooleanProperty(String propertyName) {
        try {
            return Boolean.getBoolean(propertyName);
        } catch (Exception e) {
            return false;
        }
    }

    private static void quickInterleave3(byte[] interleavedBytes, byte[] bytes, int bandSize) {
        final int bandSize2 = 2 * bandSize;
        for (int i = 0, disp = 0; i < bandSize; i++) {
            interleavedBytes[disp++] = bytes[i];
            interleavedBytes[disp++] = bytes[i + bandSize];
            interleavedBytes[disp++] = bytes[i + bandSize2];
        }
    }

    private static void quickSeparate3(byte[] separatedBytes, byte[] bytes, int bandSize) {
        final int bandSize2 = 2 * bandSize;
        for (int i = 0, disp = 0; i < bandSize; i++) {
            separatedBytes[i] = bytes[disp++];
            separatedBytes[i + bandSize] = bytes[disp++];
            separatedBytes[i + bandSize2] = bytes[disp++];
        }
    }

    private static int isAdditionalRepackNecessary(DetailedIFD ifd) throws FormatException {
        TiffCompression compression = ifd.getCompression();
        if (!DetailedIFD.isStandard(compression) || DetailedIFD.isJpeg(compression)) {
            // - JPEG codec and all non-standard codecs like JPEG2000 should perform all necessary
            // bits unpacking or color space corrections themselves
            return 1;
        }
        if (ifd.getPhotometricInterpretation() == PhotoInterp.Y_CB_CR) {
            // - convertYCbCrToRGB function performs necessary repacking itself
            return 0;
        }
        return ifd.isOrdinaryBitDepth() && !ifd.isStandardInvertedCompression() ? 2 : 0;
    }

    private static void checkInterleaved(TiffTile tile) throws FormatException {
        if (!tile.isInterleaved()) {
            throw new IllegalArgumentException("Tile data must be interleaved for correct completing " +
                    "to decode " + tile.ifd().getCompression() + " (separated data are allowed for codecs " +
                    "like JPEG, that must fully decode data themselves, but not for this " +
                    "compression): " + tile);
        }
    }

    private static int toUnsignedByte(double v) {
        return v < 0.0 ? 0 : v > 255.0 ? 255 : (int) Math.round(v);
    }

    private static int pow2(int b) {
        return 1 << b;
    }
}
