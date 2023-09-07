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

package net.algart.matrices.maps.pyramids.io.api.sources;

import net.algart.matrices.maps.pyramids.io.api.PlanePyramidSource;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidSourceFactory;

import java.io.File;
import java.io.IOException;

public class ImageIOPlanePyramidSourceFactory implements PlanePyramidSourceFactory {
    @Override
    public PlanePyramidSource newPlanePyramidSource(
        String pyramidPath,
        String pyramidConfiguration,
        String renderingConfiguration)
        throws IOException
    {
        return new net.algart.matrices.maps.pyramids.io.api.sources.ImageIOPlanePyramidSource(
            null,
            // - no cache
            new File(pyramidPath),
            new ImageIOPlanePyramidSource.ImageIOReadingBehaviour()
                .setAddAlphaWhenExist(true));
        // This plane pyramid source does not support coarse data (which can be set in renderingConfiguration)
    }
}