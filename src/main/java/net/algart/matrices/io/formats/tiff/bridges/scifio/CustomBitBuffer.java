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

/**
 * A class for reading arbitrary numbers of bits from a byte array.
 *
 * @author Eric Kjellman
 * @author Daniel Alievsky (adding position() method)
 */
class CustomBitBuffer {

    // -- Constants --

    /**
     * Various bitmasks for the 0000xxxx side of a byte.
     */
    private static final int[] BACK_MASK = {0x00, // 00000000
            0x01, // 00000001
            0x03, // 00000011
            0x07, // 00000111
            0x0F, // 00001111
            0x1F, // 00011111
            0x3F, // 00111111
            0x7F // 01111111
    };

    /**
     * Various bitmasks for the xxxx0000 side of a byte.
     */
    private static final int[] FRONT_MASK = {0x0000, // 00000000
            0x0080, // 10000000
            0x00C0, // 11000000
            0x00E0, // 11100000
            0x00F0, // 11110000
            0x00F8, // 11111000
            0x00FC, // 11111100
            0x00FE // 11111110
    };

    private final byte[] byteBuffer;

    private int currentByteIndex;

    private int currentBitIndex;

    private final int eofByteIndex;

    private boolean eofFlag;

    /**
     * Default constructor.
     */
    public CustomBitBuffer(final byte[] byteBuffer) {
        this.byteBuffer = byteBuffer;
        currentByteIndex = 0;
        currentBitIndex = 0;
        eofByteIndex = byteBuffer.length;
    }

    public long position() {
        return ((long) currentByteIndex << 3) + currentBitIndex;
    }

    /**
     * Skips a number of bits in the BitBuffer.
     *
     * @param bits Number of bits to skip
     */
    public void skipBits(final long bits) {
        if (bits < 0) {
            throw new IllegalArgumentException("Bits to skip may not be negative");
        }

        // handles skipping past eof
        if ((long) eofByteIndex * 8 < (long) currentByteIndex * 8 + currentBitIndex + bits) {
            eofFlag = true;
            currentByteIndex = eofByteIndex;
            currentBitIndex = 0;
            return;
        }

        final int skipBytes = (int) (bits / 8);
        final int skipBits = (int) (bits % 8);
        currentByteIndex += skipBytes;
        currentBitIndex += skipBits;
        while (currentBitIndex >= 8) {
            currentByteIndex++;
            currentBitIndex -= 8;
        }
    }

    /**
     * Returns an int value representing the value of the bits read from the byte
     * array, from the current position. Bits are extracted from the "left side"
     * or high side of the byte.
     * <p>
     * The current position is modified by this call.
     * <p>
     * Bits are pushed into the int from the right, endianness is not considered
     * by the method on its own. So, if 5 bits were read from the buffer "10101",
     * the int would be the integer representation of 000...0010101 on the target
     * machine.
     * <p>
     * In general, this also means the result will be positive unless a full 32
     * bits are read.
     * <p>
     * Requesting more than 32 bits is allowed, but only up to 32 bits worth of
     * data will be returned (the last 32 bits read).
     * <p>
     *
     * @param bitsToRead the number of bits to read from the bit buffer
     * @return the value of the bits read
     */
    public int getBits(int bitsToRead) {
        if (bitsToRead < 0) {
            throw new IllegalArgumentException("Bits to read may not be negative");
        }
        if (bitsToRead == 0) {
            return 0;
        }
        if (eofFlag) {
            return -1; // Already at end of file
        }
        int toStore = 0;
        while (bitsToRead != 0) {
            if (currentBitIndex < 0 || currentBitIndex > 7) {
                throw new IllegalStateException("byte=" + currentByteIndex + ", bit = " +
                        currentBitIndex);
            }

            // if we need to read from more than the current byte in the
            // buffer...
            final int bitsLeft = 8 - currentBitIndex;
            if (bitsToRead >= bitsLeft) {
                toStore <<= bitsLeft;
                bitsToRead -= bitsLeft;
                final int cb = byteBuffer[currentByteIndex];
                if (currentBitIndex == 0) {
                    // we can read in a whole byte, so we'll do that.
                    toStore += cb & 0xff;
                } else {
                    // otherwise, only read the appropriate number of bits off
                    // the back
                    // side of the byte, in order to "finish" the current byte
                    // in the
                    // buffer.
                    toStore += cb & BACK_MASK[bitsLeft];
                    currentBitIndex = 0;
                }
                currentByteIndex++;
            } else {
                // We will be able to finish using the current byte.
                // read the appropriate number of bits off the front side of the
                // byte,
                // then push them into the int.
                toStore = toStore << bitsToRead;
                final int cb = byteBuffer[currentByteIndex] & 0xff;
                toStore += (cb & (0x00FF - FRONT_MASK[currentBitIndex])) >> (bitsLeft -
                        bitsToRead);
                currentBitIndex += bitsToRead;
                bitsToRead = 0;
            }
            // If we reach the end of the buffer, return what we currently have.
            if (currentByteIndex == eofByteIndex) {
                eofFlag = true;
                return toStore;
            }
        }
        return toStore;
    }
}
