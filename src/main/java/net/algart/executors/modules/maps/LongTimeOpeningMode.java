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

package net.algart.executors.modules.maps;

public enum LongTimeOpeningMode {
    OPEN_AND_CLOSE(false, true, true),
    OPEN(false, true, false),
    OPEN_ON_RESET_AND_FIRST_CALL(true, false, false),
    OPEN_ON_FIRST_CALL(false, false, false);

    private final boolean closePreviousOnReset;
    private final boolean closePreviousOnExecute;
    private final boolean closeAfterExecute;

    LongTimeOpeningMode(boolean closePreviousOnReset, boolean closePreviousOnExecute, boolean closeAfterExecute) {
        this.closePreviousOnReset = closePreviousOnReset;
        this.closePreviousOnExecute = closePreviousOnExecute;
        this.closeAfterExecute = closeAfterExecute;
    }

    public boolean isClosePreviousOnReset() {
        return closePreviousOnReset;
    }

    public boolean isClosePreviousOnExecute() {
        return closePreviousOnExecute;
    }

    public boolean isCloseAfterExecute() {
        return closeAfterExecute;
    }
}
