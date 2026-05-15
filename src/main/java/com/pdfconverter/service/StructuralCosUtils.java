package com.pdfconverter.service;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSObject;

import java.nio.charset.StandardCharsets;

/**
 * Shared COS graph utilities for structural conversion passes.
 */
final class StructuralCosUtils {

    private StructuralCosUtils() {
    }

    static COSBase resolveGenerations(COSBase base) {
        while (base instanceof COSObject obj) {
            base = obj.getObject();
        }
        return base;
    }

    static boolean containsAscii(byte[] data, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= data.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (data[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
