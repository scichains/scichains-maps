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

package net.algart.executors.modules.maps.frames;

import net.algart.executors.api.data.SNumbers;
import net.algart.executors.api.data.SScalar;
import net.algart.executors.modules.core.common.numbers.NumbersFilter;
import net.algart.executors.modules.maps.frames.buffers.MapBuffer;
import net.algart.executors.modules.maps.frames.buffers.MapBufferKey;
import net.algart.math.IPoint;
import net.algart.math.IRectangularArea;

public final class ChangeRectangleInsideMapBuffer extends NumbersFilter {
    public static final String EXPANDED = "expanded";
    public static final String RECTANGLE = "rectangle";
    public static final String DIM_X = "dim_x";
    public static final String DIM_Y = "dim_y";
    public static final String RELATIVE_RECTANGLE = "relative_rectangle";
    public static final String RELATIVE_EXPANDED_RECTANGLE = "relative_expanded_rectangle";
    public static final String CONTAINING_RECTANGLE = "containing_rectangle";

    public enum Changing {
        LEFT_UP("left-up shift") {
            @Override
            IRectangularArea change(IRectangularArea area, long x, long y) {
                return area.shift(IPoint.valueOf(-x, -y));
            }
        },
        RIGHT_UP("right-up shift") {
            @Override
            IRectangularArea change(IRectangularArea area, long x, long y) {
                return area.shift(IPoint.valueOf(x, -y));
            }
        },
        LEFT_DOWN("left-down shift") {
            @Override
            IRectangularArea change(IRectangularArea area, long x, long y) {
                return area.shift(IPoint.valueOf(-x, y));
            }
        },
        RIGHT_DOWN("right-down shift") {
            @Override
            IRectangularArea change(IRectangularArea area, long x, long y) {
                return area.shift(IPoint.valueOf(x, y));
            }
        },
        // IMPORTANT! These names MUST be identical to names of DiagonalDirectionOnMap enum from scichains-maps,
        // to provide compatibility with it while using onChangeParameter() method
        EXPAND("expanding") {
            @Override
            IRectangularArea change(IRectangularArea area, long x, long y) {
                return area.dilate(IPoint.valueOf(x, y));
            }
        };

        private final String name;

        Changing(String name) {
            this.name = name;
        }

        abstract IRectangularArea change(IRectangularArea area, long x, long y);

    }

    private Changing changing = Changing.EXPAND;
    private int x = 0;
    private Integer y = null;
    private boolean rectangleMustBeCovered = false;

    public ChangeRectangleInsideMapBuffer() {
        addInputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        setDefaultInputNumbers(RECTANGLE);
        addInputNumbers(EXPANDED);
        setDefaultOutputNumbers(RECTANGLE);
        addOutputScalar(DIM_X);
        addOutputScalar(DIM_Y);
        addOutputNumbers(RELATIVE_RECTANGLE);
        addOutputNumbers(RELATIVE_EXPANDED_RECTANGLE);
        addOutputNumbers(CONTAINING_RECTANGLE);
        addOutputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
    }

    public Changing getChanging() {
        return changing;
    }

    public ChangeRectangleInsideMapBuffer setChanging(Changing changing) {
        this.changing = nonNull(changing);
        return this;
    }

    public int getX() {
        return x;
    }

    public ChangeRectangleInsideMapBuffer setX(int x) {
        this.x = x;
        return this;
    }

    public Integer getY() {
        return y;
    }

    public ChangeRectangleInsideMapBuffer setY(Integer y) {
        this.y = y;
        return this;
    }

    public boolean isRectangleMustBeCovered() {
        return rectangleMustBeCovered;
    }

    public ChangeRectangleInsideMapBuffer setRectangleMustBeCovered(boolean rectangleMustBeCovered) {
        this.rectangleMustBeCovered = rectangleMustBeCovered;
        return this;
    }

    @Override
    protected SNumbers processNumbers(SNumbers source) {
        final SScalar id = getInputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        final MapBuffer mapBuffer = MapBufferKey.getInstance(id.toLong()).reqMapBuffer();
        final IRectangularArea expanded = getInputNumbers(EXPANDED, true).toIRectangularArea();
        IRectangularArea area = source.toIRectangularArea();
        boolean originalAreaMustBeCovered = rectangleMustBeCovered;
        if (area == null) {
            area = mapBuffer.reqLastFrame().position();
            originalAreaMustBeCovered = true;
        }
        final IRectangularArea changed = changing.change(area, x, y != null ? y : x);
        IRectangularArea result = mapBuffer.changeRectangleOnMap(area, changed, originalAreaMustBeCovered);
        if (expanded != null) {
            final IRectangularArea cropped = result.intersection(expanded);
            if (cropped == null) {
                throw new IllegalArgumentException("Expanded rectangle " + expanded
                        + " does not intersect " + result + ", the result of " + changing.name
                        + " of " + area + " by (" + x + ", " + y + ")");
            }
            result = cropped;
            getNumbers(RELATIVE_RECTANGLE).setTo(result.shiftBack(expanded.min()));
            getNumbers(RELATIVE_EXPANDED_RECTANGLE).setTo(expanded.shiftBack(result.min()));
        }
        if (isOutputNecessary(CONTAINING_RECTANGLE)) {
            getNumbers(CONTAINING_RECTANGLE).setTo(mapBuffer.containingRectangle());
        }
        getScalar(DIM_X).setTo(result.sizeX());
        getScalar(DIM_Y).setTo(result.sizeY());
        getScalar(InitializeMapBuffer.MAP_BUFFER_ID).setTo(id);
        return SNumbers.of(result);
    }

    @Override
    protected boolean allowUninitializedInput() {
        return true;
    }
}
