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

package net.algart.matrices.io.formats.tests;


import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.*;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

public class AWTCustomMetadataWriteJpegTest {
    public static final boolean NEED_JCS_RGB = true;

    public static ImageWriter getJPEGWriter() throws IIOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IIOException("Cannot write JPEG");
        }
        return writers.next();
    }

    public static ImageWriteParam getJPEGWriteParam(ImageWriter writer, ImageTypeSpecifier imageTypeSpecifier) {
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        writeParam.setCompressionType("JPEG");
        if (imageTypeSpecifier != null) {
            writeParam.setDestinationType(imageTypeSpecifier);
            // - Important! It informs getDefaultImageMetadata to add Adobe and SOF markers,
            // that is detected by JPEGImageWriter and leads to correct outCsType = JPEG.JCS_RGB
        }
        return writeParam;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("    " + AWTCustomMetadataWriteJpegTest.class.getName()
                    + " some_image.jpeg result.jpeg");
            return;
        }

        final File srcFile = new File(args[0]);
        final File resultFile = new File(args[1]);

        System.out.printf("Opening %s...%n", srcFile);
        final ImageInputStream stream = ImageIO.createImageInputStream(srcFile);
        if (stream == null) {
            throw new IIOException("Can't create an ImageInputStream!");
        }
        BufferedImage bi = ImageIO.read(stream);
        System.out.printf("Successfully read: %s%n%n", bi);

        System.out.printf("Writing JPEG image into %s...%n", resultFile);
        resultFile.delete();
        ImageWriter writer = getJPEGWriter();
        System.out.printf("Registered writer is %s%n", writer.getClass());

        ImageOutputStream ios = ImageIO.createImageOutputStream(resultFile);
        writer.setOutput(ios);

        ImageTypeSpecifier imageTypeSpecifier = new ImageTypeSpecifier(bi);

        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        IIOMetadata metadata = writer.getDefaultImageMetadata(imageTypeSpecifier, writeParam);
        correctColorSpace(metadata, "RGB");
        IIOImage iioImage = new IIOImage(bi, null, metadata);
        // - metadata necessary (with necessary markers)
        writer.write(null, iioImage, writeParam);
    }

    static Node correctColorSpace(IIOMetadata metadata, String colorSpace) throws IIOInvalidTreeException {
        Node tree = metadata.getAsTree("javax_imageio_1.0");
        NodeList rootNodes = tree.getChildNodes();
        for (int k = 0, n = rootNodes.getLength(); k < n; k++) {
            Node rootChild = rootNodes.item(k);
            String childName = rootChild.getNodeName();
            System.out.println(childName);
            if ("Chroma".equalsIgnoreCase(childName)) {
                NodeList nodes = rootChild.getChildNodes();
                for (int i = 0, m = nodes.getLength(); i < m; i++) {
                    Node subChild = nodes.item(i);
                    String subChildName = subChild.getNodeName();
                    System.out.println("  " + subChildName);
                    if ("ColorSpaceType".equalsIgnoreCase(subChildName)) {
                        NamedNodeMap attributes = subChild.getAttributes();
                        Node name = attributes.getNamedItem("name");
                        System.out.println("    name = " + name.getNodeValue());
                        name.setNodeValue(colorSpace);
                        System.out.println("    name (new) = " + name.getNodeValue());
                    }
                }
            }
        }
        metadata.setFromTree("javax_imageio_1.0", tree);
        return tree;
    }
}
