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

import jakarta.json.JsonObject;
import net.algart.json.Jsons;
import net.algart.maps.pyramids.io.api.PlanePyramidSource;
import net.algart.maps.pyramids.io.api.PlanePyramidSourceFactory;
import net.algart.maps.pyramids.io.api.PlanePyramidTools;
import net.algart.maps.pyramids.io.api.sources.RotatingPlanePyramidSource;
import net.algart.maps.pyramids.io.formats.sources.svs.metadata.SVSAdditionalCombiningInfo;
import net.algart.matrices.tiff.TiffException;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Paths;

/*
Possible pyramidJson:
{
  format: {
    svs: {
      "combineWithWholeSlideImage": true,
      "recommendedGeometry": {
        "slideHeight": 27539.224149863763,
        "slideWidth": 82419.0845112782
      }
    }
  }
}
 */
public class SVSPlanePyramidSourceFactory implements PlanePyramidSourceFactory {
    private static final System.Logger LOG = System.getLogger(SVSPlanePyramidSource.class.getName());

    public SVSPlanePyramidSourceFactory() {
        // Deprecated solution, not necessary in new algart-tiff versions for any SVS files
//        long t1 = System.nanoTime();
//        sciContext = new SCIFIO().context();
//        long t2 = System.nanoTime();
//        LOG.log(System.Logger.Level.INFO, String.format(Locale.US,
//                "Creating %s (context %s): %.3f ms", this, sciContext, (t2 - t1) * 1e-6));
    }

    @Override
    public PlanePyramidSource newPlanePyramidSource(
            String pyramidPath,
            String pyramidConfiguration,
            String renderingConfiguration)
            throws IOException {
        final JsonObject pyramidJson = Jsons.toJson(pyramidConfiguration, true);
        final JsonObject renderingJson = Jsons.toJson(renderingConfiguration, true);
        final JsonObject pyramidFormatJson = pyramidJson.getJsonObject("format");
        JsonObject svsJson = Jsons.getJsonObject(pyramidFormatJson, "svs");
        if (svsJson == null) {
            svsJson = Jsons.getJsonObject(pyramidFormatJson, "aperio");
            // - possible alias for some old code
        }
        if (svsJson == null) {
            svsJson = Jsons.newEmptyJson();
        }

        final SVSPlanePyramidSource source;
        try {
            SVSAdditionalCombiningInfo geometry = SVSAdditionalCombiningInfo.getInstanceFromJson(
                    Jsons.getJsonObject(svsJson, "recommendedGeometry"));
            source = new SVSPlanePyramidSource(
                    Paths.get(pyramidPath),
                    svsJson.getBoolean("combineWithWholeSlideImage", false),
                    geometry);
            // "false" default value is necessary for compatibility with old slides (before March 2015)
        } catch (TiffException e) {
            throw PlanePyramidTools.rmiSafeWrapper(e);
        }
        final JsonObject coarseData = renderingJson.getJsonObject("coarseData");
        if (coarseData != null) {
            source.setSkipCoarseData(coarseData.getBoolean("skip", false));
            source.setSkippingFiller(Jsons.getDouble(coarseData, "filler", 0.0));
        }
        final JsonObject border = renderingJson.getJsonObject("border");
        if (border != null) {
            final String color = border.getString("color", "#A3A3A3");
            final int width = border.getInt("width", 2);
            source.setDataBorderColor(Color.decode(color));
            source.setDataBorderWidth(width);
        }
        final RotatingPlanePyramidSource.RotationMode rotationMode =
                RotatingPlanePyramidSource.RotationMode.of(svsJson.getInt("rotation", 0));
        if (rotationMode == RotatingPlanePyramidSource.RotationMode.NONE) {
            return source;
        } else {
            return RotatingPlanePyramidSource.newInstance(source, rotationMode);
        }
    }

//    @Override
//    public void close() {
//        Context sciContext = this.sciContext;
//        this.sciContext = null;
//        if (sciContext != null) {
//            long t1 = System.nanoTime();
//            sciContext.close();
//            long t2 = System.nanoTime();
//            LOG.log(System.Logger.Level.INFO, () -> String.format(Locale.US,
//                    "Closing SVS plane pyramid source factory (context %s): %.3f ms",
//                    sciContext, (t2 - t1) * 1e-6));
//        } else {
//            LOG.log(System.Logger.Level.WARNING,
//                    "Attempt to close already CLOSED SVS plane pyramid source factory");
//        }
//    }

    @Override
    public String toString() {
        return "SVS plane pyramid source factory";
    }
}
