package net.algart.matrices.maps.pyramids.io.formats.svs.metadata;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import net.algart.json.Jsons;

public final class SVSAdditionalCombiningInfo {
    private static final Double STANDARD_SLIDE_WIDTH = getDoubleProperty(
        "net.algart.matrices.maps.pyramids.io.formats.svs.metadata.slideWidth");
    private static final Double STANDARD_SLIDE_HEIGHT = getDoubleProperty(
        "net.algart.matrices.maps.pyramids.io.formats.svs.metadata.slideHeight");
    // - both constants are in microns (typical values for medicine are 75000x26000)

    private Double slideWidthInMicrons = null;
    private Double slideHeightInMicrons = null;

    public static SVSAdditionalCombiningInfo getInstanceFromSystemRecommendations() {
        SVSAdditionalCombiningInfo result = new SVSAdditionalCombiningInfo();
        result.setSlideWidthInMicrons(STANDARD_SLIDE_WIDTH);
        result.setSlideHeightInMicrons(STANDARD_SLIDE_HEIGHT);
        return result;
    }

    public static SVSAdditionalCombiningInfo getInstanceFromJson(JsonObject json) {
        SVSAdditionalCombiningInfo result = new SVSAdditionalCombiningInfo();
        if (json == null) {
            return result;
        }
        result.setSlideWidthInMicrons(json.containsKey("slideWidth") ?
                Jsons.reqDouble(json, "slideWidth") : null);
        result.setSlideHeightInMicrons(json.containsKey("slideHeight") ?
                Jsons.reqDouble(json, "slideHeight") : null);
        return result;
    }

    public JsonObject toJson() {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        builder.add("slideWidth", slideWidthInMicrons);
        builder.add("slideHeight", slideHeightInMicrons);
        return builder.build();
    }

    public Double getSlideWidthInMicrons() {
        return slideWidthInMicrons;
    }

    public void setSlideWidthInMicrons(Double slideWidthInMicrons) {
        if (slideWidthInMicrons != null && slideWidthInMicrons <= 0.0) {
            throw new IllegalArgumentException("Negative or zero slideWidthInMicrons = " + slideWidthInMicrons);
        }
        this.slideWidthInMicrons = slideWidthInMicrons;
    }

    public Double getSlideHeightInMicrons() {
        return slideHeightInMicrons;
    }

    public void setSlideHeightInMicrons(Double slideHeightInMicrons) {
        if (slideHeightInMicrons != null && slideHeightInMicrons <= 0.0) {
            throw new IllegalArgumentException("Negative or zero slideHeightInMicrons = " + slideHeightInMicrons);
        }
        this.slideHeightInMicrons = slideHeightInMicrons;
    }

    private static Double getDoubleProperty(String propertyName) {
        try {
            final String s = System.getProperty(propertyName);
            if (s != null) {
                return Double.valueOf(s);
            }
        } catch (Exception e) {
            // SecurityException or NumberFormatException
        }
        return null;
    }
}
