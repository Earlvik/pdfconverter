package com.pdfconverter.service;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects use of device CMYK (catalog, content operators, resources, images, patterns) for output-intent choice.
 */
final class StructuralCmykDetection {

    private static final Logger log = LoggerFactory.getLogger(StructuralCmykDetection.class);

    private static final COSName DEFAULT_CMYK = COSName.getPDFName("DefaultCMYK");
    private static final COSName DEFAULT_GRAY = COSName.getPDFName("DefaultGray");
    private static final COSName DEFAULT_RGB = COSName.getPDFName("DefaultRGB");

    boolean documentUsesDeviceCmyk(PDDocument doc) {
        if (containsDeviceCmykInCosTree(doc)) {
            return true;
        }
        if (containsCmykPaintOperatorsInDocument(doc)) {
            return true;
        }
        for (PDPage page : doc.getPages()) {
            if (pageContentReferencesDeviceCmyk(page)) {
                return true;
            }
        }
        Set<COSDictionary> visited = new HashSet<>();
        for (PDPage page : doc.getPages()) {
            if (containsDeviceCmykInResources(page.getResources(), visited)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detects CMYK painting operators (k / K) in any page or Form XObject content stream.
     */
    private boolean containsCmykPaintOperatorsInDocument(PDDocument doc) {
        Set<COSBase> visited = new HashSet<>();
        return containsCmykPaintOperatorsTraverse(doc.getDocumentCatalog().getCOSObject(), visited);
    }

    private boolean containsCmykPaintOperatorsTraverse(COSBase base, Set<COSBase> visited) {
        base = StructuralCosUtils.resolveGenerations(base);
        if (base == null || !visited.add(base)) {
            return false;
        }
        if (base instanceof COSStream stream) {
            COSName subtype = stream.getCOSName(COSName.SUBTYPE);
            if (COSName.FORM.equals(subtype)) {
                try (InputStream in = stream.createInputStream()) {
                    if (contentBytesUseCmykPaintOperators(in.readAllBytes())) {
                        return true;
                    }
                } catch (IOException e) {
                    log.warn("Skipping Form XObject content when scanning for CMYK operators (non-critical): {}", e.getMessage());
                }
            }
        }
        if (base instanceof COSDictionary dict) {
            for (COSBase value : dict.getValues()) {
                if (containsCmykPaintOperatorsTraverse(value, visited)) {
                    return true;
                }
            }
        } else if (base instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (containsCmykPaintOperatorsTraverse(array.get(i), visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Parses a decoded content stream and returns true if CMYK paint operators {@code k} / {@code K} appear.
     */
    private boolean contentBytesUseCmykPaintOperators(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        PDFStreamParser parser = new PDFStreamParser(data);
        try {
            List<Object> tokens = parser.parse();
            for (Object o : tokens) {
                if (o instanceof Operator op) {
                    String name = op.getName();
                    if ("k".equals(name) || "K".equals(name)) {
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("PDFStreamParser failed on content snippet for CMYK scan (non-critical): {}", e.getMessage());
            return false;
        } finally {
            try {
                parser.close();
            } catch (IOException e) {
                log.warn("PDFStreamParser close failed after CMYK scan (non-critical): {}", e.getMessage());
            }
        }
        return false;
    }

    private boolean containsDeviceCmykInCosTree(PDDocument doc) {
        Set<COSBase> visited = new HashSet<>();
        return containsDeviceCmykTraverse(doc.getDocumentCatalog().getCOSObject(), visited);
    }

    private boolean containsDeviceCmykTraverse(COSBase base, Set<COSBase> visited) {
        base = StructuralCosUtils.resolveGenerations(base);
        if (base == null || !visited.add(base)) {
            return false;
        }
        if (COSName.DEVICECMYK.equals(base)) {
            return true;
        }
        if (base instanceof COSArray arr) {
            if (arr.size() > 0 && COSName.DEVICECMYK.equals(StructuralCosUtils.resolveGenerations(arr.get(0)))) {
                return true;
            }
            for (int i = 0; i < arr.size(); i++) {
                if (containsDeviceCmykTraverse(arr.get(i), visited)) {
                    return true;
                }
            }
            return false;
        }
        if (base instanceof COSDictionary dict) {
            for (COSBase value : dict.getValues()) {
                if (containsDeviceCmykTraverse(value, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean pageContentReferencesDeviceCmyk(PDPage page) {
        try {
            if (!page.hasContents()) {
                return false;
            }
            try (InputStream in = page.getContents()) {
                byte[] data = in.readAllBytes();
                return StructuralCosUtils.containsAscii(data, "/DeviceCMYK")
                        || StructuralCosUtils.containsAscii(data, "DeviceCMYK")
                        || contentBytesUseCmykPaintOperators(data);
            }
        } catch (IOException e) {
            log.warn("Could not read page contents for DeviceCMYK hint (non-critical): {}", e.getMessage());
            return false;
        }
    }

    /**
     * Recursively checks resource dictionaries for device CMYK (named spaces, defaults, images, forms, patterns).
     */
    private boolean containsDeviceCmykInResources(PDResources resources, Set<COSDictionary> visited) {
        if (resources == null) {
            return false;
        }
        COSDictionary resDict = resources.getCOSObject();
        if (resDict == null || !visited.add(resDict)) {
            return false;
        }
        for (COSName name : resources.getColorSpaceNames()) {
            try {
                PDColorSpace cs = resources.getColorSpace(name);
                if (cs instanceof PDDeviceCMYK) {
                    return true;
                }
            } catch (IOException e) {
                log.warn("Could not resolve color space {} for CMYK detection (non-critical, skipping entry): {}", name, e.getMessage());
            }
        }
        for (COSName key : new COSName[]{DEFAULT_CMYK, DEFAULT_GRAY, DEFAULT_RGB}) {
            COSBase def = resDict.getItem(key);
            if (def != null && cosBaseIndicatesDeviceCmyk(def, resources)) {
                return true;
            }
        }
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xobj = resources.getXObject(name);
                if (xobj instanceof PDImageXObject img) {
                    PDColorSpace cs = img.getColorSpace();
                    if (cs instanceof PDDeviceCMYK) {
                        return true;
                    }
                } else if (xobj instanceof PDFormXObject form) {
                    if (containsDeviceCmykInResources(form.getResources(), visited)) {
                        return true;
                    }
                }
            } catch (IOException e) {
                log.warn("Could not inspect XObject for CMYK detection (non-critical): {}", e.getMessage());
            }
        }
        COSDictionary xoDict = resDict.getCOSDictionary(COSName.XOBJECT);
        if (xoDict != null) {
            for (COSName xName : xoDict.keySet()) {
                COSBase xBase = StructuralCosUtils.resolveGenerations(xoDict.getItem(xName));
                if (xBase instanceof COSStream xs
                        && COSName.IMAGE.equals(xs.getCOSName(COSName.SUBTYPE))
                        && imageStreamUsesDeviceCmyk(xs, resources)) {
                    return true;
                }
            }
        }
        for (COSName pName : resources.getPatternNames()) {
            try {
                PDAbstractPattern pattern = resources.getPattern(pName);
                if (pattern instanceof PDTilingPattern tiling) {
                    if (containsDeviceCmykInResources(tiling.getResources(), visited)) {
                        return true;
                    }
                }
            } catch (IOException e) {
                log.warn("Could not inspect pattern {} for CMYK detection (non-critical): {}", pName, e.getMessage());
            }
        }
        return false;
    }

    private boolean imageStreamUsesDeviceCmyk(COSStream imgDict, PDResources resources) {
        COSBase cs = imgDict.getItem(COSName.COLORSPACE);
        if (cs == null) {
            cs = imgDict.getItem(COSName.getPDFName("ColorSpace"));
        }
        return cosBaseIndicatesDeviceCmyk(cs, resources);
    }

    private boolean cosBaseIndicatesDeviceCmyk(COSBase cs, PDResources resources) {
        cs = StructuralCosUtils.resolveGenerations(cs);
        if (cs == null) {
            return false;
        }
        if (COSName.DEVICECMYK.equals(cs)) {
            return true;
        }
        if (cs instanceof COSArray arr && arr.size() > 0) {
            COSBase first = StructuralCosUtils.resolveGenerations(arr.get(0));
            if (COSName.DEVICECMYK.equals(first)) {
                return true;
            }
        }
        if (cs instanceof COSName cn && resources != null) {
            try {
                PDColorSpace pdcs = resources.getColorSpace(cn);
                if (pdcs instanceof PDDeviceCMYK) {
                    return true;
                }
            } catch (IOException e) {
                log.warn("Could not resolve named color space for CMYK check (non-critical): {}", e.getMessage());
            }
        }
        return false;
    }
}
