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

package net.algart.executors.modules.maps.tiff;

import io.scif.SCIFIO;
import net.algart.executors.api.Executor;
import net.algart.executors.modules.core.common.io.FileOperation;
import net.algart.executors.modules.maps.LongTimeOpeningMode;
import net.algart.matrices.tiff.TiffReader;
import org.scijava.Context;

import java.util.Objects;

public abstract class AbstractTiffOperation extends FileOperation {
    public static final String INPUT_CLOSE_FILE = "close_file";
    public static final String OUTPUT_NUMBER_OF_LEVELS = "number_of_levels";
    public static final String OUTPUT_LEVEL_DIM_X = "level_dim_x";
    public static final String OUTPUT_LEVEL_DIM_Y = "level_dim_y";
    public static final String OUTPUT_IFD = "ifd";
    public static final String OUTPUT_PRETTY_IFD = "pretty_ifd";

    private boolean useContext = false;

    private volatile Context tiffContext = null;
    private final Object lock = new Object();

    public AbstractTiffOperation() {
    }

    public boolean isUseContext() {
        return useContext;
    }

    public AbstractTiffOperation setUseContext(boolean useContext) {
        this.useContext = useContext;
        return this;
    }

    @Override
    public void close() {
        super.close();
        closeContext();
    }

    public final Context context() {
        return useContext ? resetContext() : null;
    }

    public final Context resetContext() {
        synchronized (lock) {
            if (tiffContext == null) {
                tiffContext = new SCIFIO().context();
            }
            return tiffContext;
        }
    }

    public final void closeContext() {
        synchronized (lock) {
            if (tiffContext != null) {
                tiffContext.close();
                tiffContext = null;
            }
        }
    }

    public static boolean needToClose(Executor executor, LongTimeOpeningMode openingMode) {
        Objects.requireNonNull(executor, "Null executor");
        Objects.requireNonNull(openingMode, "Null openingMode");
        if (openingMode.isCloseAfterExecute()) {
            return true;
        }
        final String s = executor.getInputScalar(INPUT_CLOSE_FILE).getValue();
        return Boolean.parseBoolean(s);
    }
}
