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
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
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
 * Detects use of device RGB for pairing with CMYK detection when choosing a single output intent.
 */
final class StructuralRgbDetection {

    private static final Logger log = LoggerFactory.getLogger(StructuralRgbDetection.class);

    boolean documentUsesDeviceRgb(PDDocument doc) {
        if (containsDeviceRgbInCosTree(doc)) {
            return true;
        }
        if (containsRgbPaintOperatorsInDocument(doc)) {
            return true;
        }
        for (PDPage page : doc.getPages()) {
            if (pageContentReferencesDeviceRgb(page)) {
                return true;
            }
        }
        Set<COSDictionary> visited = new HashSet<>();
        for (PDPage page : doc.getPages()) {
            if (containsDeviceRgbInResources(page.getResources(), visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsDeviceRgbInCosTree(PDDocument doc) {
        Set<COSBase> visited = new HashSet<>();
        return containsDeviceRgbTraverse(doc.getDocumentCatalog().getCOSObject(), visited);
    }

    private boolean containsDeviceRgbTraverse(COSBase base, Set<COSBase> visited) {
        base = StructuralCosUtils.resolveGenerations(base);
        if (base == null || !visited.add(base)) {
            return false;
        }
        if (COSName.DEVICERGB.equals(base)) {
            return true;
        }
        if (base instanceof COSArray arr) {
            if (arr.size() > 0 && COSName.DEVICERGB.equals(StructuralCosUtils.resolveGenerations(arr.get(0)))) {
                return true;
            }
            for (int i = 0; i < arr.size(); i++) {
                if (containsDeviceRgbTraverse(arr.get(i), visited)) {
                    return true;
                }
            }
            return false;
        }
        if (base instanceof COSDictionary dict) {
            for (COSBase value : dict.getValues()) {
                if (containsDeviceRgbTraverse(value, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsRgbPaintOperatorsInDocument(PDDocument doc) {
        Set<COSBase> visited = new HashSet<>();
        return containsRgbPaintOperatorsTraverse(doc.getDocumentCatalog().getCOSObject(), visited);
    }

    private boolean containsRgbPaintOperatorsTraverse(COSBase base, Set<COSBase> visited) {
        base = StructuralCosUtils.resolveGenerations(base);
        if (base == null || !visited.add(base)) {
            return false;
        }
        if (base instanceof COSStream stream) {
            COSName subtype = stream.getCOSName(COSName.SUBTYPE);
            if (COSName.FORM.equals(subtype)) {
                try (InputStream in = stream.createInputStream()) {
                    if (contentBytesUseRgbPaintOperators(in.readAllBytes())) {
                        return true;
                    }
                } catch (IOException e) {
                    log.warn("Skipping Form XObject when scanning for RGB operators (non-critical): {}", e.getMessage());
                }
            }
        }
        if (base instanceof COSDictionary dict) {
            for (COSBase value : dict.getValues()) {
                if (containsRgbPaintOperatorsTraverse(value, visited)) {
                    return true;
                }
            }
        } else if (base instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (containsRgbPaintOperatorsTraverse(array.get(i), visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean pageContentReferencesDeviceRgb(PDPage page) {
        try {
            if (!page.hasContents()) {
                return false;
            }
            try (InputStream in = page.getContents()) {
                byte[] data = in.readAllBytes();
                return StructuralCosUtils.containsAscii(data, "/DeviceRGB") || contentBytesUseRgbPaintOperators(data);
            }
        } catch (IOException e) {
            log.warn("Could not read page contents for DeviceRGB hint (non-critical): {}", e.getMessage());
            return false;
        }
    }

    private boolean contentBytesUseRgbPaintOperators(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        PDFStreamParser parser = new PDFStreamParser(data);
        try {
            List<Object> tokens = parser.parse();
            for (Object o : tokens) {
                if (o instanceof Operator op) {
                    String name = op.getName();
                    if ("rg".equals(name) || "RG".equals(name)) {
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("PDFStreamParser failed on RGB operator scan (non-critical): {}", e.getMessage());
            return false;
        } finally {
            try {
                parser.close();
            } catch (IOException e) {
                log.warn("PDFStreamParser close failed after RGB scan (non-critical): {}", e.getMessage());
            }
        }
        return false;
    }

    private boolean containsDeviceRgbInResources(PDResources resources, Set<COSDictionary> visited) {
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
                if (cs instanceof PDDeviceRGB) {
                    return true;
                }
            } catch (IOException e) {
                log.warn("Could not resolve color space {} for RGB detection (non-critical): {}", name, e.getMessage());
            }
        }
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xobj = resources.getXObject(name);
                if (xobj instanceof PDImageXObject img) {
                    PDColorSpace cs = img.getColorSpace();
                    if (cs instanceof PDDeviceRGB) {
                        return true;
                    }
                } else if (xobj instanceof PDFormXObject form) {
                    if (containsDeviceRgbInResources(form.getResources(), visited)) {
                        return true;
                    }
                }
            } catch (IOException e) {
                log.warn("Could not inspect XObject for RGB detection (non-critical): {}", e.getMessage());
            }
        }
        COSDictionary xoDict = resDict.getCOSDictionary(COSName.XOBJECT);
        if (xoDict != null) {
            for (COSName xName : xoDict.keySet()) {
                COSBase xBase = StructuralCosUtils.resolveGenerations(xoDict.getItem(xName));
                if (xBase instanceof COSStream xs
                        && COSName.IMAGE.equals(xs.getCOSName(COSName.SUBTYPE))
                        && imageStreamUsesDeviceRGB(xs, resources)) {
                    return true;
                }
            }
        }
        for (COSName pName : resources.getPatternNames()) {
            try {
                PDAbstractPattern pattern = resources.getPattern(pName);
                if (pattern instanceof PDTilingPattern tiling) {
                    if (containsDeviceRgbInResources(tiling.getResources(), visited)) {
                        return true;
                    }
                }
            } catch (IOException e) {
                log.warn("Could not inspect pattern {} for RGB detection (non-critical): {}", pName, e.getMessage());
            }
        }
        return false;
    }

    private boolean imageStreamUsesDeviceRGB(COSStream imgDict, PDResources resources) {
        COSBase cs = imgDict.getItem(COSName.COLORSPACE);
        if (cs == null) {
            cs = imgDict.getItem(COSName.getPDFName("ColorSpace"));
        }
        return cosBaseIndicatesDeviceRGB(cs, resources);
    }

    private boolean cosBaseIndicatesDeviceRGB(COSBase cs, PDResources resources) {
        cs = StructuralCosUtils.resolveGenerations(cs);
        if (cs == null) {
            return false;
        }
        if (COSName.DEVICERGB.equals(cs)) {
            return true;
        }
        if (cs instanceof COSArray arr && arr.size() > 0) {
            COSBase first = StructuralCosUtils.resolveGenerations(arr.get(0));
            if (COSName.DEVICERGB.equals(first)) {
                return true;
            }
        }
        if (cs instanceof COSName cn && resources != null) {
            try {
                PDColorSpace pdcs = resources.getColorSpace(cn);
                if (pdcs instanceof PDDeviceRGB) {
                    return true;
                }
            } catch (IOException e) {
                log.warn("Could not resolve named color space for RGB check (non-critical): {}", e.getMessage());
            }
        }
        return false;
    }
}
