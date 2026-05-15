package com.pdfconverter.service;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Normalizes ICC profile streams embedded in ICCBased color spaces (PDF/A §6.2.3.2).
 */
final class StructuralIccRepair {

    private static final Logger log = LoggerFactory.getLogger(StructuralIccRepair.class);

    /**
     * Walks the document graph for arrays {@code [/ICCBased stream]} and fixes each profile stream for VeraPDF §6.2.3.2.
     */
    void fixIccBasedStreams(PDDocument doc) {
        Set<COSBase> visited = new HashSet<>();
        fixIccTraverse(doc.getDocumentCatalog().getCOSObject(), visited);
    }

    private void fixIccTraverse(COSBase base, Set<COSBase> visited) {
        base = StructuralCosUtils.resolveGenerations(base);
        if (base == null || !visited.add(base)) {
            return;
        }
        if (base instanceof COSDictionary dict) {
            for (COSBase value : dict.getValues()) {
                fixIccTraverse(value, visited);
            }
        } else if (base instanceof COSArray array) {
            if (array.size() >= 2 && COSName.ICCBASED.equals(StructuralCosUtils.resolveGenerations(array.get(0)))) {
                COSBase streamObj = StructuralCosUtils.resolveGenerations(array.get(1));
                if (streamObj instanceof COSStream iccStream) {
                    alignIccStreamNWithProfile(iccStream);
                }
            }
            for (int i = 0; i < array.size(); i++) {
                fixIccTraverse(array.get(i), visited);
            }
        }
    }

    private void alignIccStreamNWithProfile(COSStream stream) {
        byte[] profileBytes;
        try (InputStream in = stream.createInputStream()) {
            profileBytes = in.readAllBytes();
        } catch (IOException e) {
            log.warn("Could not read ICC stream, using fallback profile (non-critical): {}", e.getMessage());
            replaceIccStreamWithFallback(stream);
            return;
        }
        if (profileBytes.length < 128) {
            replaceIccStreamWithFallback(stream);
            return;
        }
        final ICC_Profile profile;
        try {
            profile = ICC_Profile.getInstance(profileBytes);
        } catch (IllegalArgumentException e) {
            replaceIccStreamWithFallback(stream);
            return;
        }
        int n = profile.getNumComponents();
        if (n < 1 || n > 4) {
            replaceIccStreamWithFallback(stream);
            return;
        }
        try {
            stream.removeItem(COSName.FILTER);
            stream.removeItem(COSName.DECODE_PARMS);
            try (OutputStream os = stream.createOutputStream()) {
                os.write(profile.getData());
            }
            stream.setInt(COSName.N, n);
        } catch (IOException e) {
            log.warn("ICC stream rewrite failed after parse (non-critical for whole-document conversion): {}", e.getMessage());
        }
    }

    /**
     * Replaces a corrupt or non-standard ICC stream with a bundled or JDK profile so VeraPDF accepts it.
     */
    private void replaceIccStreamWithFallback(COSStream stream) {
        try {
            int declaredN = stream.containsKey(COSName.N) ? stream.getInt(COSName.N) : 3;
            byte[] data;
            int n;
            if (declaredN == 4) {
                try (InputStream is = StructuralIccRepair.class.getResourceAsStream(StructuralBundledResources.ICC_DEFAULT_CMYK)) {
                    if (is == null) {
                        log.warn("Missing bundled resource {} — cannot replace invalid CMYK ICC stream", StructuralBundledResources.ICC_DEFAULT_CMYK);
                        return;
                    }
                    data = is.readAllBytes();
                }
                n = 4;
            } else if (declaredN == 1) {
                data = ICC_Profile.getInstance(ColorSpace.CS_GRAY).getData();
                n = 1;
            } else {
                data = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
                n = 3;
            }
            stream.removeItem(COSName.FILTER);
            stream.removeItem(COSName.DECODE_PARMS);
            try (OutputStream os = stream.createOutputStream()) {
                os.write(data);
            }
            stream.setInt(COSName.N, n);
        } catch (Exception e) {
            log.warn("ICC fallback replace failed (non-critical; file may still fail this rule elsewhere): {}", e.getMessage());
        }
    }
}
