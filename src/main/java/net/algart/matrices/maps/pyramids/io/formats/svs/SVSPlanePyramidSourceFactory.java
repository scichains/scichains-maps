package net.algart.matrices.maps.pyramids.io.formats.svs;

import io.scif.FormatException;
import io.scif.SCIFIO;
import jakarta.json.JsonObject;
import net.algart.json.Jsons;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidSource;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidSourceFactory;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidTools;
import net.algart.matrices.maps.pyramids.io.api.sources.RotatingPlanePyramidSource;
import net.algart.matrices.maps.pyramids.io.formats.svs.metadata.SVSAdditionalCombiningInfo;
import org.scijava.Context;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Locale;

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

    private volatile Context sciContext;

    public SVSPlanePyramidSourceFactory() {
        long t1 = System.nanoTime();
        sciContext = new SCIFIO().context();
        long t2 = System.nanoTime();
        LOG.log(System.Logger.Level.INFO, String.format(Locale.US,
                "Creating %s (context %s): %.3f ms", this, sciContext, (t2 - t1) * 1e-6));
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
                    Jsons.getJsonObject(svsJson,"recommendedGeometry"));
            source = new SVSPlanePyramidSource(
                    sciContext,
                    Paths.get(pyramidPath),
                    svsJson.getBoolean("combineWithWholeSlideImage", false),
                    geometry);
            // "false" default value is necessary for compatibility with old slides (before March 2015)
        } catch (FormatException e) {
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
                RotatingPlanePyramidSource.RotationMode.valueOf(svsJson.getInt("rotation", 0));
        if (rotationMode == RotatingPlanePyramidSource.RotationMode.NONE) {
            return source;
        } else {
            return RotatingPlanePyramidSource.newInstance(source, rotationMode);
        }
    }

    @Override
    public void close() {
        Context sciContext = this.sciContext;
        this.sciContext = null;
        if (sciContext != null) {
            long t1 = System.nanoTime();
            sciContext.close();
            long t2 = System.nanoTime();
            LOG.log(System.Logger.Level.INFO, () -> String.format(Locale.US,
                    "Closing SVS plane pyramid source factory (context %s): %.3f ms",
                    sciContext, (t2 - t1) * 1e-6));
        } else {
            LOG.log(System.Logger.Level.WARNING,
                    "Attempt to close already CLOSED SVS plane pyramid source factory");
        }
    }

    @Override
    public String toString() {
        return "SVS plane pyramid source factory" + (sciContext == null ? " (CLOSED)" : "");
    }
}
