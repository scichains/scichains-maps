package net.algart.matrices.maps.pyramids.io.formats.svs.tests;

import io.scif.FormatException;
import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.executors.api.data.SMat;
import net.algart.json.Jsons;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidSource;
import net.algart.matrices.maps.pyramids.io.formats.svs.SVSPlanePyramidSource;
import net.algart.matrices.maps.pyramids.io.formats.svs.SVSPlanePyramidSourceFactory;
import net.algart.matrices.maps.pyramids.io.formats.svs.metadata.SVSImageDescription;
import net.algart.multimatrix.MultiMatrix;
import net.algart.multimatrix.MultiMatrix2D;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class SVSSourceTest {
    private static final int MAX_IMAGE_DIM = 10000;

    private static final int START_X = 0;
    // - non-zero value allows to illustrate a bug in TiffParser.getSamples
    private static final int START_Y = 0;

    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        boolean combine = false;
        if (args.length > startArgIndex && args[startArgIndex].equals("-combine")) {
            combine = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 3) {
            System.out.println("Usage:");
            System.out.println("    " + SVSSourceTest.class.getName()
                    + " [-combine] some_file.svs level result.png");
            return;
        }
        final String sourceFile = args[startArgIndex];
        final int level = Integer.parseInt(args[startArgIndex + 1]);
        final File resultFile = new File(args[startArgIndex + 2]);

        final String pyramidConfiguration = """
                {
                    "format": {
                        "aperio": {
                            "combineWithWholeSlideImage": %s,
                            "recommendedGeometry": {
                                "slideWidth": 75000,
                                "slideHeight": 26000
                            }
                        }
                    }
                }
                """.formatted(combine);
        final String renderingConfiguration = """
                {
                    "border": {
                        "color": "#FFFF00",
                        "width": 20
                    }
                }
                """;
        // - these JSONs are used when -combine flag is set
        System.out.printf("Opening %s...%n", sourceFile);
        SVSPlanePyramidSourceFactory factory = new SVSPlanePyramidSourceFactory();
        PlanePyramidSource source = factory.newPlanePyramidSource(
                sourceFile, pyramidConfiguration, renderingConfiguration);
        final int w = (int) Math.min(source.width(level), MAX_IMAGE_DIM);
        final int h = (int) Math.min(source.height(level), MAX_IMAGE_DIM);

        if (source instanceof SVSPlanePyramidSource svsSource) {
            SVSImageDescription description = svsSource.mainImageDescription();
            System.out.printf("Description:%n%s%nGeometry support: %s%n",
                    Jsons.toPrettyString(description.toJson()),
                    description.isGeometrySupported());
            System.out.printf("IFD classification: %s%n", svsSource.getIfdClassifier());
            System.out.println();
        }

        MultiMatrix2D multiMatrix = null;
        for (int test = 1; test <= 5; test++) {
            if (test == 1) {
                System.out.printf("Reading data %dx%dx%d from level #%d/%d %s[%dx%d]%n",
                        w, h, source.bandCount(),
                        level, source.numberOfResolutions(),
                        source.elementType().getSimpleName(), source.width(level), source.height(level));
            }
            long t1 = System.nanoTime();
            Matrix<? extends PArray> m = source.readSubMatrix(level, START_X, START_Y, w, h);
            long t2 = System.nanoTime();
            multiMatrix = MultiMatrix.valueOf2DRGBA(SMat.unpackBandsFromSequentialSamples(m));
            long t3 = System.nanoTime();
            System.out.printf("Test #%d: %dx%d (%.3f MB) loaded in %.3f ms + %.3f ms, %.3f MB/sec%n",
                    test, w, h, Matrices.sizeOf(m) / 1048576.0,
                    (t2 - t1) * 1e-6, (t3 - t2) * 1e-6,
                    (Matrices.sizeOf(m) / 1048576.0 / ((t3 - t1) * 1e-9)));
        }

        System.out.printf("Converting data to BufferedImage...%n");
        final BufferedImage image = SMat.valueOf(multiMatrix).toBufferedImage();
        System.out.printf("Saving result image into %s...%n", resultFile);
        if (!ImageIO.write(image, "png", resultFile)) {
            throw new IIOException("Cannot write " + resultFile);
        }
        source.freeResources(PlanePyramidSource.FlushMode.STANDARD);
        factory.close();
        System.out.println("Done");
    }
}
