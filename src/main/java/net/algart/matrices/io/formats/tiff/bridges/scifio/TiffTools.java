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
import io.scif.formats.tiff.FillOrder;
import io.scif.formats.tiff.IFD;
import io.scif.util.FormatTools;
import org.scijava.io.location.FileLocation;
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

public class TiffTools {
    static final boolean BUILT_IN_TIMING = getBooleanProperty(
            "net.algart.matrices.io.formats.tiff.builtInTiming");
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

    public static int arrayToPixelType(Object javaArray, boolean signed) {
        Objects.requireNonNull(javaArray, "Null Java array");
        Class<?> elementType = javaArray.getClass().getComponentType();
        if (elementType == null) {
            throw new IllegalArgumentException("The specified javaArray is not actual an array: " +
                    "it is " + javaArray.getClass());
        }
        return elementTypeToPixelType(elementType, signed);
    }

    public static int elementTypeToPixelType(Class<?> elementType, boolean signed) {
        Objects.requireNonNull(elementType, "Null elementType");
        if (elementType == byte.class) {
            return signed ? FormatTools.INT8 : FormatTools.UINT8;
        } else if (elementType == short.class) {
            return signed ? FormatTools.INT16 : FormatTools.UINT16;
        } else if (elementType == int.class) {
            return signed ? FormatTools.INT32 : FormatTools.UINT32;
        } else if (elementType == float.class) {
            return FormatTools.FLOAT;
        } else if (elementType == double.class) {
            return FormatTools.DOUBLE;
        } else {
            throw new IllegalArgumentException("Element type " + elementType +
                    " is unsupported: it cannot be converted to pixel type");
        }
    }

    public static Object planeBytesToJavaArray(byte[] samples, int pixelType, boolean littleEndian) {
        //TODO!! optimize and test all types
        switch (pixelType) {
            case FormatTools.INT8, FormatTools.UINT8 -> {
                return samples;
            }
            case FormatTools.INT16, FormatTools.UINT16 -> {
                final short[] shortValues = new short[samples.length / 2];
                final ByteBuffer bb = ByteBuffer.wrap(samples);
                bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                bb.asShortBuffer().get(shortValues);
                return shortValues;
            }
            case FormatTools.INT32, FormatTools.UINT32 -> {
                final int[] intValues = new int[samples.length / 4];
                final ByteBuffer bb = ByteBuffer.wrap(samples);
                bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                bb.asIntBuffer().get(intValues);
                return intValues;
            }
            case FormatTools.FLOAT -> {
                final float[] floatValues = new float[samples.length / 4];
                final ByteBuffer bb = ByteBuffer.wrap(samples);
                bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                bb.asFloatBuffer().get(floatValues);
                return floatValues;
            }
            case FormatTools.DOUBLE -> {
                final double[] doubleValues = new double[samples.length / 8];
                final ByteBuffer bb = ByteBuffer.wrap(samples);
                bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                bb.asDoubleBuffer().get(doubleValues);
                return doubleValues;
            }
        }
        throw new IllegalArgumentException("Unknown pixel type: " + pixelType);
    }

    public static byte[] javaArrayToPlaneBytes(Object samplesArray, boolean signed, boolean littleEndian) {
        int pixelType = arrayToPixelType(samplesArray, signed);
        //TODO!! optimize and test all conversions
        switch (pixelType) {
            case FormatTools.INT8, FormatTools.UINT8 -> {
                return (byte[]) samplesArray;
            }
            case FormatTools.INT16, FormatTools.UINT16 -> {
                return Bytes.fromShorts((short[]) samplesArray, littleEndian);
            }
            case FormatTools.INT32, FormatTools.UINT32 -> {
                return Bytes.fromInts((int[]) samplesArray, littleEndian);
            }
            case FormatTools.FLOAT -> {
                return Bytes.fromFloats((float[]) samplesArray, littleEndian);
            }
            case FormatTools.DOUBLE -> {
                return Bytes.fromDoubles((double[]) samplesArray, littleEndian);
            }
        }
        throw new AssertionError("(should be already checked in arrayToPixelType)");
    }

    public static void interleaveSamples(IFD ifd, byte[] samples, int numberOfPixels) throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        interleaveSamples(
                samples,
                ifd.getSamplesPerPixel(),
                FormatTools.getBytesPerPixel(ifd.getPixelType()),
                numberOfPixels);
    }

    public static void interleaveSamples(byte[] bytes, int numberOfChannels, int bytesPerSample, int numberOfPixels) {
        byte[] buffer = toInterleavedSamples(
                bytes, numberOfChannels, bytesPerSample,
                numberOfPixels);
        if (buffer != bytes) {
            System.arraycopy(buffer, 0, bytes, 0, buffer.length);
        }
    }

    public static byte[] toInterleavedSamples(
            byte[] bytes,
            int numberOfChannels,
            int bytesPerSample,
            int numberOfPixels) {
        Objects.requireNonNull(bytes, "Null bytes");
        if (numberOfPixels < 0) {
            throw new IllegalArgumentException("Negative numberOfPixels = " + numberOfPixels);
        }
        if (numberOfChannels <= 0) {
            throw new IllegalArgumentException("Zero or negative numberOfChannels = " + numberOfChannels);
        }
        if (bytesPerSample <= 0) {
            throw new IllegalArgumentException("Zero or negative bytesPerSample = " + bytesPerSample);
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
            // optimization
            for (int i = 0, disp = 0; i < bandSize; i++) {
                for (int bandDisp = i, j = 0; j < numberOfChannels; j++, bandDisp += bandSize) {
                    // note: we must check j, not bandDisp, because "bandDisp += bandSize" can lead to overflow
                    interleavedBytes[disp++] = bytes[bandDisp];
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

    public static void unpackUnusualPrecisions(IFD ifd, byte[] samples, int numberOfPixels) {
        Objects.requireNonNull(samples, "Null samples");
        Objects.requireNonNull(ifd, "Null IFD");
        if (numberOfPixels < 0) {
            throw new IllegalArgumentException("Negative numberOfPixels = " + numberOfPixels);
        }
        final boolean float16;
        final boolean float24;
        final boolean littleEndian;
        final int samplesPerPixel;
        try {
            final int pixelType = ifd.getPixelType();
            if (pixelType != FormatTools.FLOAT) {
                return;
            }
            samplesPerPixel = ifd.getSamplesPerPixel();
            final int[] bitsPerSample = ifd.getBitsPerSample();
            float16 = bitsPerSample[0] == 16;
            float24 = bitsPerSample[0] == 24;
            littleEndian = ifd.isLittleEndian();
        } catch (FormatException e) {
            throw new IllegalArgumentException("Illegal TIFF IFD", e);
            // - usually should not occur: this function is typically called after analysing IFD
        }

        if (float16 || float24) {
            // - very rare case, no sense to optimize the following code (it was already tested inside SCIFIO)
            final int nBytes = float16 ? 2 : 3;
            final int mantissaBits = float16 ? 10 : 16;
            final int exponentBits = float16 ? 5 : 7;
            final int maxExponent = (int) Math.pow(2, exponentBits) - 1;
            final int bits = (nBytes * 8) - 1;

            checkedMulNoException(new long[]{numberOfPixels, samplesPerPixel, nBytes},
                    new String[]{"number of pixels", "samples per pixel", "bytes per sample"},
                    () -> "Invalid sizes: ", () -> "");
            // - exception usually should not occur: this function is typically called after analysing IFD
            final int numberOfSamples = samplesPerPixel * numberOfPixels;

            final byte[] newBuf = new byte[samples.length];
            for (int i = 0; i < numberOfSamples; i++) {
                final int v = Bytes.toInt(samples, i * nBytes, nBytes, littleEndian);
                final int sign = v >> bits;
                int exponent = (v >> mantissaBits) & (int) (Math.pow(2, exponentBits) - 1);
                int mantissa = v & (int) (Math.pow(2, mantissaBits) - 1);

                if (exponent == 0) {
                    if (mantissa != 0) {
                        while ((mantissa & (int) Math.pow(2, mantissaBits)) == 0) {
                            mantissa <<= 1;
                            exponent--;
                        }
                        exponent++;
                        mantissa &= (int) (Math.pow(2, mantissaBits) - 1);
                        exponent += 127 - (Math.pow(2, exponentBits - 1) - 1);
                    }
                } else if (exponent == maxExponent) {
                    exponent = 255;
                } else {
                    exponent += 127 - (Math.pow(2, exponentBits - 1) - 1);
                }

                mantissa <<= (23 - mantissaBits);

                final int value = (sign << 31) | (exponent << 23) | mantissa;
                Bytes.unpack(value, newBuf, i * 4, 4, littleEndian);
            }
            System.arraycopy(newBuf, 0, samples, 0, newBuf.length);
        }
    }

    /**
     * Changes bits order inside the passed array if FillOrder=2.
     * Note that {@link TiffSaver} does not support this. GIMP and most viewers also do not support this feature.
     *
     * @param ifd IFD
     * @param buf bytes
     * @throws FormatException in a case of error in IFD
     */
    public static void invertFillOrderIfNecessary(final IFD ifd, final byte[] buf) throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        if (ifd.getFillOrder() == FillOrder.REVERSED) {
            // swap bits order of all bytes
            for (int i = 0; i < buf.length; i++) {
                buf[i] = REVERSE[buf[i] & 0xFF];
            }
        }
    }

    static FileLocation existingFileToLocation(Path file) throws FileNotFoundException {
        if (!Files.isRegularFile(file)) {
            throw new FileNotFoundException("File " + file
                    + (Files.exists(file) ? " is not a regular file" : " does not exist"));
        }
        return new FileLocation(file.toFile());
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

    static void checkRequestedArea(long fromX, long fromY, long sizeX, long sizeY) {
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

    static void checkRequestedArea(long fromX, long fromY, long sizeX, long sizeY, long imageSizeX, long imageSizeY) {
        checkRequestedArea(fromX, fromY, sizeX, sizeY);
        if (imageSizeX < 0 || imageSizeY < 0) {
            throw new IllegalArgumentException("Negative imageSizeX = " + imageSizeX +
                    " or imageSizeY = " + imageSizeY);
        }
        if (fromX > imageSizeX - sizeX || fromY > imageSizeY - sizeY) {
            throw new IllegalArgumentException("Requested area [" + fromX + ".." + (fromX + sizeX - 1) +
                    " x " + fromY + ".." + (fromY + sizeY - 1) + " is out of image ranges " +
                    imageSizeX + "x" + imageSizeY);
        }
    }

    static boolean getBooleanProperty(String propertyName) {
        try {
            return Boolean.getBoolean(propertyName);
        } catch (Exception e) {
            return false;
        }
    }
}
