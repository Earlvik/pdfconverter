package com.pdfconverter.service;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * PDF/A-1 string length limits and LZW → Flate stream normalization.
 */
final class StructuralStreamSanitizer {

    private static final Logger log = LoggerFactory.getLogger(StructuralStreamSanitizer.class);

    void fixLzwAndStrings(PDDocument doc) {
        Set<COSBase> visited = new HashSet<>();
        processCosObject(doc.getDocumentCatalog().getCOSObject(), visited);
    }

    private void processCosObject(COSBase base, Set<COSBase> visited) {
        if (base instanceof COSObject co) {
            base = co.getObject();
        }

        if (base == null || !visited.add(base)) {
            return;
        }

        if (base instanceof COSDictionary dict) {
            for (COSBase value : dict.getValues()) {
                processCosObject(value, visited);
            }
        } else if (base instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                processCosObject(array.getObject(i), visited);
            }
        } else if (base instanceof COSString str) {
            byte[] bytes = str.getBytes();
            if (bytes.length > 65535) {
                byte[] truncated = new byte[65535];
                System.arraycopy(bytes, 0, truncated, 0, 65535);
                str.setValue(truncated);
            }
        }

        if (base instanceof COSStream stream) {
            COSBase filters = stream.getFilters();
            if (filters != null) {
                boolean hasLzw = false;
                if (COSName.LZW_DECODE.equals(filters) || COSName.LZW_DECODE_ABBREVIATION.equals(filters)) {
                    hasLzw = true;
                } else if (filters instanceof COSArray farr) {
                    for (int i = 0; i < farr.size(); i++) {
                        COSBase f = farr.getObject(i);
                        if (COSName.LZW_DECODE.equals(f) || COSName.LZW_DECODE_ABBREVIATION.equals(f)) {
                            hasLzw = true;
                            break;
                        }
                    }
                }

                if (hasLzw) {
                    try (InputStream is = stream.createInputStream()) {
                        byte[] decoded = is.readAllBytes();
                        try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
                            os.write(decoded);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to convert single LZW stream to Flate (non-critical for rest of document): {}", e.getMessage());
                    }
                }
            }
        }
    }
}
