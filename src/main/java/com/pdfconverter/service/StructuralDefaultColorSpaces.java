package com.pdfconverter.service;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Injects DefaultRGB, DefaultCMYK, and DefaultGray into all Resource dictionaries.
 * This maps uncalibrated device color spaces (DeviceRGB, DeviceCMYK, DeviceGray)
 * to compliant ICCBased color spaces, allowing a single Output Intent for the document
 * without violating PDF/A-1b rules when mixed device color spaces are used.
 */
class StructuralDefaultColorSpaces {

    private static final Logger log = LoggerFactory.getLogger(StructuralDefaultColorSpaces.class);

    private static final COSName DEFAULT_RGB = COSName.getPDFName("DefaultRGB");
    private static final COSName DEFAULT_CMYK = COSName.getPDFName("DefaultCMYK");
    private static final COSName DEFAULT_GRAY = COSName.getPDFName("DefaultGray");

    void addDefaultColorSpaces(PDDocument doc) {
        try {
            COSArray defaultRgb = createIccBasedArray(doc, ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData(), 3);
            COSArray defaultGray = createIccBasedArray(doc, ICC_Profile.getInstance(ColorSpace.CS_GRAY).getData(), 1);

            byte[] cmykData;
            try (InputStream is = StructuralDefaultColorSpaces.class.getResourceAsStream(StructuralBundledResources.ICC_DEFAULT_CMYK)) {
                if (is == null) {
                    throw new IllegalStateException("CMYK ICC Profile resource not found");
                }
                cmykData = is.readAllBytes();
            }
            COSArray defaultCmyk = createIccBasedArray(doc, cmykData, 4);

            Set<COSBase> visited = new HashSet<>();
            for (PDPage page : doc.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) {
                    resources = new PDResources();
                    page.setResources(resources);
                }
                injectIntoResources(resources, defaultRgb, defaultCmyk, defaultGray, visited);
            }

            log.debug("Injected DefaultRGB, DefaultCMYK, and DefaultGray to calibrate all device color usages");
        } catch (Exception e) {
            log.warn("Failed to inject Default color spaces: {}", e.getMessage(), e);
        }
    }

    private COSArray createIccBasedArray(PDDocument doc, byte[] iccData, int numComponents) throws Exception {
        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream os = stream.createOutputStream()) {
            os.write(iccData);
        }
        stream.setInt(COSName.N, numComponents);

        COSArray array = new COSArray();
        array.add(COSName.ICCBASED);
        array.add(stream);
        return array;
    }

    private void injectIntoResources(PDResources resources, COSArray defaultRgb, COSArray defaultCmyk, COSArray defaultGray, Set<COSBase> visited) {
        if (resources == null) {
            return;
        }

        COSDictionary cosResources = resources.getCOSObject();
        if (!visited.add(cosResources)) {
            return; // Already processed this dictionary (prevents infinite recursion)
        }

        // Add Default color spaces to ColorSpace dictionary
        COSDictionary colorSpaces;
        COSBase csBase = cosResources.getDictionaryObject(COSName.COLORSPACE);
        if (csBase instanceof COSDictionary dict) {
            colorSpaces = dict;
        } else {
            colorSpaces = new COSDictionary();
            cosResources.setItem(COSName.COLORSPACE, colorSpaces);
        }

        if (!colorSpaces.containsKey(DEFAULT_RGB)) {
            colorSpaces.setItem(DEFAULT_RGB, defaultRgb);
        }
        if (!colorSpaces.containsKey(DEFAULT_CMYK)) {
            colorSpaces.setItem(DEFAULT_CMYK, defaultCmyk);
        }
        if (!colorSpaces.containsKey(DEFAULT_GRAY)) {
            colorSpaces.setItem(DEFAULT_GRAY, defaultGray);
        }

        // Recurse into XObjects (Forms)
        for (COSName name : resources.getXObjectNames()) {
            try {
                if (!resources.isImageXObject(name)) {
                    PDXObject xobj = resources.getXObject(name);
                    if (xobj instanceof PDFormXObject form) {
                        PDResources formResources = form.getResources();
                        if (formResources == null) {
                            formResources = new PDResources();
                            form.setResources(formResources);
                        }
                        injectIntoResources(formResources, defaultRgb, defaultCmyk, defaultGray, visited);
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to check XObject {} for nested resources: {}", name, e.getMessage());
            }
        }

        // Recurse into Patterns (Tiling patterns have their own resources)
        for (COSName name : resources.getPatternNames()) {
            try {
                PDAbstractPattern pattern = resources.getPattern(name);
                if (pattern instanceof PDTilingPattern tiling) {
                    PDResources tilingResources = tiling.getResources();
                    if (tilingResources == null) {
                        tilingResources = new PDResources();
                        tiling.setResources(tilingResources);
                    }
                    injectIntoResources(tilingResources, defaultRgb, defaultCmyk, defaultGray, visited);
                }
            } catch (Exception e) {
                log.debug("Failed to check Pattern {} for nested resources: {}", name, e.getMessage());
            }
        }
    }
}
