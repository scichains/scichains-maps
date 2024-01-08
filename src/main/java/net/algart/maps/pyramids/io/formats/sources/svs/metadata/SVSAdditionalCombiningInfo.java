/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2024 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.maps.pyramids.io.formats.sources.svs.metadata;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import net.algart.json.Jsons;

public final class SVSAdditionalCombiningInfo {
    private static final Double STANDARD_SLIDE_WIDTH = getDoubleProperty(
        "net.algart.maps.pyramids.io.formats.sources.svs.metadata.slideWidth");
    private static final Double STANDARD_SLIDE_HEIGHT = getDoubleProperty(
        "net.algart.maps.pyramids.io.formats.sources.svs.metadata.slideHeight");
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
