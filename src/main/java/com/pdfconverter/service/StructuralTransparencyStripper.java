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
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Strips transparency-related constructs (SMask, soft masks, non-normal blend, group transparency) for PDF/A-1.
 */
final class StructuralTransparencyStripper {

    private static final Logger log = LoggerFactory.getLogger(StructuralTransparencyStripper.class);

    void stripTransparency(PDDocument doc) {
        Set<COSDictionary> visited = new HashSet<>();
        for (PDPage page : doc.getPages()) {
            stripTransparencyFromResources(page.getResources(), visited);

            COSBase group = page.getCOSObject().getDictionaryObject(COSName.GROUP);
            if (group instanceof COSDictionary gDict) {
                if (COSName.TRANSPARENCY.equals(gDict.getDictionaryObject(COSName.S))) {
                    page.getCOSObject().removeItem(COSName.GROUP);
                }
            }
        }
        stripSmaskFromAllXObjectDictionaries(doc);
    }

    /**
     * Removes /SMask from every Form or Image XObject anywhere in the document (including indirect-only graphs).
     */
    private void stripSmaskFromAllXObjectDictionaries(PDDocument doc) {
        Set<COSBase> visited = new HashSet<>();
        stripSmaskTraverseCos(doc.getDocumentCatalog().getCOSObject(), visited);
    }

    private void stripSmaskTraverseCos(COSBase base, Set<COSBase> visited) {
        base = StructuralCosUtils.resolveGenerations(base);
        if (base == null || !visited.add(base)) {
            return;
        }
        if (base instanceof COSDictionary dict) {
            COSName subtype = dict.getCOSName(COSName.SUBTYPE);
            COSName type = dict.getCOSName(COSName.TYPE);
            boolean isImageOrForm = COSName.IMAGE.equals(subtype) || COSName.FORM.equals(subtype);
            boolean isXObject = type == null || COSName.XOBJECT.equals(type);
            if (isImageOrForm && isXObject) {
                dict.removeItem(COSName.SMASK);
                dict.removeItem(COSName.SMASK_IN_DATA);
            }
            for (COSBase value : dict.getValues()) {
                stripSmaskTraverseCos(value, visited);
            }
        } else if (base instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                stripSmaskTraverseCos(array.get(i), visited);
            }
        }
    }

    private void stripTransparencyFromResources(PDResources resources, Set<COSDictionary> visited) {
        if (resources == null) {
            return;
        }
        COSDictionary resDict = resources.getCOSObject();
        if (resDict == null || !visited.add(resDict)) {
            return;
        }

        for (COSName name : resources.getExtGStateNames()) {
            PDExtendedGraphicsState extGState = resources.getExtGState(name);
            if (extGState != null) {
                COSDictionary dict = extGState.getCOSObject();
                dict.removeItem(COSName.SMASK);
                if (dict.containsKey(COSName.BM)) {
                    dict.setItem(COSName.BM, COSName.NORMAL);
                }
                if (extGState.getStrokingAlphaConstant() != null && extGState.getStrokingAlphaConstant() < 1.0f) {
                    extGState.setStrokingAlphaConstant(1.0f);
                }
                if (extGState.getNonStrokingAlphaConstant() != null && extGState.getNonStrokingAlphaConstant() < 1.0f) {
                    extGState.setNonStrokingAlphaConstant(1.0f);
                }
            }
        }

        for (COSName name : resources.getXObjectNames()) {
            try {
                stripTransparencyFromXObject(resources.getXObject(name), visited);
            } catch (IOException e) {
                log.warn("Failed to process XObject for transparency: {}", e.getMessage());
            }
        }
        COSDictionary xoDict = resDict.getCOSDictionary(COSName.XOBJECT);
        if (xoDict != null) {
            for (COSName xName : xoDict.keySet()) {
                try {
                    PDXObject xo = resources.getXObject(xName);
                    if (xo != null) {
                        stripTransparencyFromXObject(xo, visited);
                    } else {
                        COSBase raw = StructuralCosUtils.resolveGenerations(xoDict.getItem(xName));
                        if (raw instanceof COSStream xstream) {
                            stripSoftMaskFromImageLikeDictionary(xstream);
                        }
                    }
                } catch (IOException e) {
                    log.warn("Failed to process raw XObject {}: {}", xName, e.getMessage());
                }
            }
        }
        for (COSName pName : resources.getPatternNames()) {
            try {
                PDAbstractPattern pattern = resources.getPattern(pName);
                if (pattern instanceof PDTilingPattern tiling) {
                    stripTransparencyFromResources(tiling.getResources(), visited);
                }
            } catch (IOException e) {
                log.warn("Failed to strip transparency in pattern: {}", e.getMessage());
            }
        }
    }

    private void stripTransparencyFromXObject(PDXObject xObject, Set<COSDictionary> visited) {
        if (xObject == null) {
            return;
        }
        COSDictionary dict = xObject.getCOSObject();
        dict.removeItem(COSName.SMASK);
        stripSoftMaskFromImageLikeDictionary(dict);

        COSBase group = dict.getDictionaryObject(COSName.GROUP);
        if (group instanceof COSDictionary gDict) {
            if (COSName.TRANSPARENCY.equals(gDict.getDictionaryObject(COSName.S))) {
                dict.removeItem(COSName.GROUP);
            }
        }
        if (xObject instanceof PDFormXObject form) {
            stripTransparencyFromResources(form.getResources(), visited);
        }
    }

    private void stripSoftMaskFromImageLikeDictionary(COSDictionary dict) {
        dict.removeItem(COSName.SMASK);
        dict.removeItem(COSName.SMASK_IN_DATA);
    }
}
