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

package net.algart.executors.modules.maps.pyramids.io;

import jakarta.json.*;
import net.algart.arrays.Arrays;
import net.algart.arrays.TooLargeArrayException;
import net.algart.contours.ContourHeader;
import net.algart.contours.Contours;
import net.algart.json.Jsons;
import net.algart.math.IRectangularArea;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class ImagePyramidMetadataJson {
    public static final String APP_NAME = "image-pyramid-metadata";
    public static final String CURRENT_VERSION = "1.0";

    private static final String APP_NAME_ALIAS = "plane-pyramid-metadata";

    public static abstract class Roi {
        public enum Shape {
            RECTANGLE("rectangle", Rectangle::new),
            POLYGON("polygon", Polygon::new),
            MULTIPOLYGON("multipolygon", MultiPolygon::new);

            private final String shapeName;
            private final BiFunction<JsonObject, Path, Roi> creator;

            Shape(String shapeName, BiFunction<JsonObject, Path, Roi> creator) {
                this.shapeName = Objects.requireNonNull(shapeName);
                this.creator = Objects.requireNonNull(creator);
            }

            public static Collection<String> shapeNames() {
                return java.util.Arrays.stream(values()).map(Shape::shapeName).toList();
            }

            public String shapeName() {
                return shapeName;
            }

            public static Optional<Shape> fromShapeName(String shapeName) {
                for (Shape shape : values()) {
                    if (shape.shapeName.equals(shapeName)) {
                        return Optional.of(shape);
                    }
                }
                return Optional.empty();
            }
        }

        Roi() {
        }

        static Roi of(JsonValue json, Path file) {
            if (!(json instanceof JsonObject)) {
                throw new AssertionError("json should be checked before, " +
                        "for example by Jsons.getJsonArray");
            }
            return of((JsonObject) json, file);
        }

        static Roi of(JsonObject json, Path file) {
            final String shapeName = Jsons.reqString(json, "shape", file);
            final Shape shape = Shape.fromShapeName(shapeName).orElseThrow(
                    () -> Jsons.badValue(json, "shape", shapeName, Shape.shapeNames(), file));
            final Roi result = shape.creator.apply(json, file);
            assert result.getShape() == shape : "Illegal getShape() method of " + result.getClass()
                    + ": it returns " + result.getShape() + " instead of " + shape;
            return result;
        }

        public abstract Shape getShape();

        public final JsonObject toJson() {
            final JsonObjectBuilder builder = Json.createObjectBuilder();
            toJson(builder);
            return builder.build();
        }

        /**
         * // Note: returns no results, if the corresponding area does not contain any pixels.
         * // Note: rectangle is interpreted in terms of pixels .
         * // For example, ROI containing all matrix 100x100 should be a rectangle minX=minY=0, maxX=maxY=9).
         *
         * @return containing rectangle in terms of boundaries of pixels.
         */
        public abstract Optional<IRectangularArea> containingRectangle();

        public abstract Contours contours(int label);

        public Contours scaledContours(int label, double scaleX, double scaleY) {
            return contours(label)
                    .transformContours(scaleX, scaleY, 0.0, 0.0, true);
        }

        void toJson(JsonObjectBuilder builder) {
            builder.add("shape", getShape().shapeName);
        }
    }

    public static final class Rectangle extends Roi {
        private int left;
        private int top;
        private int width;
        private int height;

        public Rectangle() {
        }

        private Rectangle(JsonObject json, Path file) {
            this.left = Jsons.reqInt(json, "left", file);
            this.top = Jsons.reqInt(json, "top", file);
            if (json.containsKey("right")) {
                this.width = Jsons.reqInt(json, "right", file) - this.left;
            } else {
                this.width = Jsons.reqInt(json, "width", file);
            }
            if (json.containsKey("bottom")) {
                this.height = Jsons.reqInt(json, "bottom", file) - this.top;
            } else {
                this.height = Jsons.reqInt(json, "height", file);
            }
        }

        @Override
        public Shape getShape() {
            return Shape.RECTANGLE;
        }

        public int getLeft() {
            return left;
        }

        public Rectangle setLeft(int left) {
            this.left = left;
            return this;
        }

        public int getTop() {
            return top;
        }

        public Rectangle setTop(int top) {
            this.top = top;
            return this;
        }

        public int getWidth() {
            return width;
        }

        public Rectangle setWidth(int width) {
            this.width = width;
            return this;
        }

        public int getHeight() {
            return height;
        }

        public Rectangle setHeight(int height) {
            this.height = height;
            return this;
        }

        @Override
        public Optional<IRectangularArea> containingRectangle() {
            return width <= 0 || height <= 0 ?
                    Optional.empty() :
                    Optional.of(IRectangularArea.valueOf(left, top, left + width - 1, top + height - 1));
        }

        @Override
        public Contours contours(int label) {
            final Optional<IRectangularArea> rectangle = containingRectangle();
            final Contours result = Contours.newInstance();
            if (rectangle.isPresent()) {
                final IRectangularArea r = rectangle.get();
                result.addContour(
                        new ContourHeader(label),
                        new int[]{
                                Arrays.round32(r.minX()),
                                Arrays.round32(r.minY()),
                                Arrays.round32(r.maxX() + 1.0),
                                Arrays.round32(r.minY()),
                                Arrays.round32(r.maxX() + 1.0),
                                Arrays.round32(r.maxY() + 1.0),
                                Arrays.round32(r.minX()),
                                Arrays.round32(r.maxY() + 1.0)});
            }
            return result;
        }

        @Override
        public String toString() {
            return "Rectangle{" +
                    "left=" + left +
                    ", top=" + top +
                    ", width=" + width +
                    ", height=" + height +
                    '}';
        }

        @Override
        void toJson(JsonObjectBuilder builder) {
            super.toJson(builder);
            builder.add("left", left);
            builder.add("top", top);
            builder.add("width", width);
            builder.add("height", height);
        }
    }

    public static final class Polygon extends Roi {
        public static class Point {
            private double x;
            private double y;

            public Point() {
            }

            private Point(JsonObject json, Path file) {
                this.x = Jsons.reqDouble(json, "x", file);
                this.y = Jsons.reqDouble(json, "y", file);
                if (!(Double.isFinite(x) && Double.isFinite(y))) {
                    throw new JsonException("Illegal point (" + x + ", " + y
                            + ") " + (file == null ? "" : "in " + file)
                            + ": it is not an ordinary point with finite coordinates");
                }
            }

            public double getX() {
                return x;
            }

            public Point setX(double x) {
                this.x = x;
                return this;
            }

            public double getY() {
                return y;
            }

            public Point setY(double y) {
                this.y = y;
                return this;
            }

            public final JsonObject toJson() {
                final JsonObjectBuilder builder = Json.createObjectBuilder();
                builder.add("x", x);
                builder.add("y", y);
                return builder.build();
            }

            @Override
            public String toString() {
                return "Point{" +
                        "x=" + x +
                        ", y=" + y +
                        '}';
            }
        }

        private List<Point> vertices = new ArrayList<>();

        public Polygon() {
        }

        private Polygon(JsonObject json, Path file) {
            for (JsonValue jsonValue : Jsons.reqJsonArray(json, "vertices", file, true)) {
                this.vertices.add(new Point((JsonObject) jsonValue, file));
            }
        }

        @Override
        public Shape getShape() {
            return Shape.POLYGON;
        }

        public List<Point> getVertices() {
            return Collections.unmodifiableList(vertices);
        }

        public Polygon setVertices(List<Point> vertices) {
            this.vertices = new ArrayList<>(Objects.requireNonNull(vertices, "Null vertices"));
            return this;
        }

        @Override
        public Optional<IRectangularArea> containingRectangle() {
            if (vertices.size() <= 1) {
                return Optional.empty();
            }
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (Point point : vertices) {
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
            }
            final long longMinX = (long) Math.floor(minX);
            final long longMinY = (long) Math.floor(minY);
            final long longMaxX = (long) Math.ceil(maxX);
            final long longMaxY = (long) Math.ceil(maxY);
            return longMaxX <= longMinX || longMaxY <= longMinY ?
                    Optional.empty() :
                    Optional.of(IRectangularArea.valueOf(longMinX, longMinY, longMaxX - 1, longMaxY - 1));
        }

        @Override
        public Contours contours(int label) {
            final Contours contours = Contours.newInstance();
            if (vertices.size() > 1) {
                final int[] verticesXY = roundedVerticesXY();
                final boolean internal = Contours.area(verticesXY, 0, verticesXY.length) < 0.0;
                contours.addContour(new ContourHeader(label, internal), verticesXY);
            }
            return contours;
        }

        public int[] roundedVerticesXY() {
            final int n = vertices.size();
            if (n > Integer.MAX_VALUE / 2) {
                throw new TooLargeArrayException("Too large array of vertices");
            }
            final int[] result = new int[2 * n];
            int disp = 0;
            for (Point v : vertices) {
                result[disp++] = Arrays.round32(v.x);
                result[disp++] = Arrays.round32(v.y);
            }
            return result;
        }

        @Override
        public String toString() {
            return "Polygon{" +
                    "vertices=" + vertices +
                    '}';
        }

        @Override
        void toJson(JsonObjectBuilder builder) {
            super.toJson(builder);
            final JsonArrayBuilder verticesBuilder = Json.createArrayBuilder();
            for (Point point : vertices) {
                verticesBuilder.add(point.toJson());
            }
            builder.add("vertices", verticesBuilder.build());
        }
    }

    public static final class MultiPolygon extends Roi {
        private List<Polygon> polygons = new ArrayList<>();

        public MultiPolygon() {
        }

        private MultiPolygon(JsonObject json, Path file) {
            for (JsonValue jsonValue : Jsons.reqJsonArray(json, "polygons", file, true)) {
                this.polygons.add(new Polygon((JsonObject) jsonValue, file));
            }
        }

        @Override
        public Shape getShape() {
            return Shape.MULTIPOLYGON;
        }

        public List<Polygon> getPolygons() {
            return Collections.unmodifiableList(polygons);
        }

        public MultiPolygon setPolygons(List<Polygon> polygons) {
            this.polygons = new ArrayList<>(Objects.requireNonNull(polygons, "Null polygons"));
            return this;
        }

        @Override
        public Optional<IRectangularArea> containingRectangle() {
            final List<IRectangularArea> nonEmpty = polygons.stream()
                    .map(Roi::containingRectangle)
                    .flatMap(Optional::stream)
                    .collect(Collectors.toList());
            return Optional.ofNullable(IRectangularArea.minimalContainingArea(nonEmpty));
        }

        @Override
        public Contours contours(int label) {
            final Contours contours = Contours.newInstance();
            for (Polygon polygon : polygons) {
                contours.addContours(polygon.contours(label));
            }
            return contours;
        }

        @Override
        public String toString() {
            return "MultiPolygon{" +
                    "polygons=" + polygons +
                    '}';
        }

        @Override
        void toJson(JsonObjectBuilder builder) {
            super.toJson(builder);
            final JsonArrayBuilder polygonsBuilder = Json.createArrayBuilder();
            for (Polygon polygon : polygons) {
                polygonsBuilder.add(polygon.toJson());
            }
            builder.add("vertices", polygonsBuilder.build());
        }
    }

    private Path metadataJsonFile = null;
    private String version = CURRENT_VERSION;
    private List<Roi> rois;
    private List<IRectangularArea> roiRectangles;

    public ImagePyramidMetadataJson() {
    }

    private ImagePyramidMetadataJson(JsonObject json, Path file) {
        if (!isPlanePyramidMetadataJson(json)) {
            throw new JsonException("JSON" + (file == null ? "" : " " + file)
                    + " is not a plane-pyramid metadata: no \"app\":\"" + APP_NAME + "\" element");
        }
        this.metadataJsonFile = file;
        this.version = json.getString("version", CURRENT_VERSION);
        final JsonArray roisJson = Jsons.getJsonArray(json, "rois", file, true);
        if (roisJson != null) {
            setRois(roisJson.stream().map(value -> Roi.of(value, file)).collect(Collectors.toList()), file);
        }
    }

    public static ImagePyramidMetadataJson read(Path planePyramidMetadataJsonFile) throws IOException {
        Objects.requireNonNull(planePyramidMetadataJsonFile, "Null planePyramidMetadataJsonFile");
        final JsonObject json = Jsons.readJson(planePyramidMetadataJsonFile);
        return new ImagePyramidMetadataJson(json, planePyramidMetadataJsonFile);
    }

    public void write(Path planePyramidMetadataJsonFile, OpenOption... options) throws IOException {
        Objects.requireNonNull(planePyramidMetadataJsonFile, "Null planePyramidMetadataJsonFile");
        Files.writeString(planePyramidMetadataJsonFile, Jsons.toPrettyString(toJson()), options);
    }

    public static ImagePyramidMetadataJson of(JsonObject metadataJson) {
        return new ImagePyramidMetadataJson(metadataJson, null);
    }

    public static boolean isPlanePyramidMetadataJson(JsonObject metadataJson) {
        Objects.requireNonNull(metadataJson, "Null plane-pyramid metadata JSON");
        final String app = metadataJson.getString("app", null);
        return APP_NAME.equals(app) || APP_NAME_ALIAS.equals(app);
    }

    public Path getMetadataJsonFile() {
        return metadataJsonFile;
    }

    public String getVersion() {
        return version;
    }

    public ImagePyramidMetadataJson setVersion(String version) {
        this.version = Objects.requireNonNull(version, "Null version");
        return this;
    }

    public List<Roi> getRois() {
        return Collections.unmodifiableList(rois);
    }

    public ImagePyramidMetadataJson setRois(List<? extends Roi> rois) {
        return setRois(rois, null);
    }

    // Note: result MAY be empty
    public List<IRectangularArea> roiRectangles() {
        return Collections.unmodifiableList(roiRectangles);
    }

    public List<IRectangularArea> roiRectangles(double scaleX, double scaleY, IRectangularArea restriction) {
        if (scaleX <= 0.0) {
            throw new IllegalArgumentException("Zero or negative scaleX = " + scaleX);
        }
        if (scaleY <= 0.0) {
            throw new IllegalArgumentException("Zero or negative scaleY = " + scaleY);
        }
        final List<IRectangularArea> result = new ArrayList<>();
        for (IRectangularArea r : roiRectangles) {
            assert r.coordCount() == 2 : r.coordCount() + " dimensions in ROI";
            final long sizeX = (long) Math.ceil(r.sizeX() * scaleX);
            final long sizeY = (long) Math.ceil(r.sizeY() * scaleY);
            if (sizeX <= 0 || sizeY <= 0) {
                // - impossible; to be on the safe side
                continue;
            }
            final long minX = Math.round(r.minX() * scaleX);
            final long minY = Math.round(r.minY() * scaleY);
            r = IRectangularArea.valueOf(minX, minY, minX + sizeX - 1, minY + sizeY - 1);
            if (restriction != null) {
                r = r.intersection(restriction);
            }
            if (r != null) {
                result.add(r);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static double[] allRoiCentersAndSizes(List<IRectangularArea> rectangles) {
        Objects.requireNonNull(rectangles, "Null rectangles");
        if (4L * (long) rectangles.size() > Integer.MAX_VALUE) {
            throw new TooLargeArrayException("Too large number of rois");
        }
        final double[] result = new double[4 * rectangles.size()];
        int disp = 0;
        for (final IRectangularArea r : rectangles) {
            pushRectangle(result, disp, r);
            disp += 4;
        }
        return result;
    }

    public List<Contours> roiContours(double scaleX, double scaleY) {
        final List<Contours> roiContours = new ArrayList<>();
        int label = 1;
        for (Roi roi : rois) {
            roiContours.add(roi.scaledContours(label++, scaleX, scaleY));
        }
        return roiContours;
    }

    public Contours allRoiContours(double scaleX, double scaleY) {
        final Contours allRoiContours = Contours.newInstance();
        int label = 1;
        for (Roi roi : rois) {
            allRoiContours.addContours(roi.scaledContours(label++, scaleX, scaleY));
        }
        return allRoiContours;
    }

    public JsonObject toJson() {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        builder.add("app", APP_NAME);
        builder.add("version", version);
        final JsonArrayBuilder roisBuilder = Json.createArrayBuilder();
        for (Roi roi : rois) {
            roisBuilder.add(roi.toJson());
        }
        builder.add("rois", roisBuilder.build());
        return builder.build();
    }

    public String jsonString() {
        return Jsons.toPrettyString(toJson());
    }

    @Override
    public String toString() {
        return "PlanePyramidMetadataJson{" +
                "metadataJsonFile=" + metadataJsonFile +
                ", version='" + version + '\'' +
                ", rois=" + rois +
                '}';
    }

    private ImagePyramidMetadataJson setRois(List<? extends Roi> rois, Path file) {
        Objects.requireNonNull(rois, "Null rois");
        final List<Roi> roisList = new ArrayList<>(rois);
        final List<IRectangularArea> roiRectangles = roisList.stream().map(Roi::containingRectangle)
                .flatMap(Optional::stream).collect(Collectors.toList());
        this.rois = roisList;
        this.roiRectangles = roiRectangles;
        return this;
    }

    private static void pushRectangle(double[] result, int offset, IRectangularArea r) {
        result[offset++] = 0.5 * ((double) r.minX() + (double) r.maxX());
        result[offset++] = 0.5 * ((double) r.minY() + (double) r.maxY());
        result[offset++] = (double) r.sizeX();
        result[offset++] = (double) r.sizeY();
    }
}
