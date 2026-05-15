package com.pdfconverter.service;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Embeds a fallback TrueType font for non-embedded base fonts and repairs CID Type 2 fonts (Identity, CIDSet).
 */
final class StructuralFontFixes {

    private static final Logger log = LoggerFactory.getLogger(StructuralFontFixes.class);

    void fixFonts(PDDocument doc) {
        PDTrueTypeFont fallbackFont = loadFallbackFont(doc);
        if (fallbackFont == null) {
            return;
        }
        Set<COSDictionary> visited = new HashSet<>();
        for (PDPage page : doc.getPages()) {
            fixFontsInResources(page.getResources(), fallbackFont, visited);
            try {
                for (PDAnnotation ann : page.getAnnotations()) {
                    PDAppearanceDictionary ap = ann.getAppearance();
                    if (ap != null) {
                        processAppearanceEntry(ap.getNormalAppearance(), fallbackFont, visited);
                        processAppearanceEntry(ap.getRolloverAppearance(), fallbackFont, visited);
                        processAppearanceEntry(ap.getDownAppearance(), fallbackFont, visited);
                    }
                }
            } catch (IOException e) {
                log.warn("Could not read annotations on page for appearance font fix (non-critical): {}", e.getMessage());
            }
        }
    }

    void fixCidFonts(PDDocument doc) {
        Set<COSDictionary> visited = new HashSet<>();
        for (PDPage page : doc.getPages()) {
            fixCidFontsInResources(page.getResources(), doc, visited);
            try {
                for (PDAnnotation ann : page.getAnnotations()) {
                    PDAppearanceDictionary ap = ann.getAppearance();
                    if (ap != null) {
                        processAppearanceForCid(ap.getNormalAppearance(), doc, visited);
                        processAppearanceForCid(ap.getRolloverAppearance(), doc, visited);
                        processAppearanceForCid(ap.getDownAppearance(), doc, visited);
                    }
                }
            } catch (IOException e) {
                log.warn("Could not read annotations on page for CID font fix (non-critical): {}", e.getMessage());
            }
        }
    }

    private PDTrueTypeFont loadFallbackFont(PDDocument doc) {
        try {
            return PDTrueTypeFont.load(doc,
                    StructuralFontFixes.class.getResourceAsStream(StructuralBundledResources.FONT_FALLBACK),
                    WinAnsiEncoding.INSTANCE);
        } catch (Exception e) {
            log.warn("Could not load fallback font from {} (structural font substitution disabled): {}",
                    StructuralBundledResources.FONT_FALLBACK, e.getMessage());
            return null;
        }
    }

    private void processAppearanceEntry(PDAppearanceEntry entry, PDTrueTypeFont fallback, Set<COSDictionary> visited) {
        if (entry == null) {
            return;
        }
        if (entry.isStream()) {
            PDAppearanceStream s = entry.getAppearanceStream();
            if (s != null) {
                fixFontsInResources(s.getResources(), fallback, visited);
            }
        } else if (entry.isSubDictionary()) {
            for (PDAppearanceStream s : entry.getSubDictionary().values()) {
                if (s != null) {
                    fixFontsInResources(s.getResources(), fallback, visited);
                }
            }
        }
    }

    private void fixFontsInResources(PDResources resources, PDTrueTypeFont fallback, Set<COSDictionary> visited) {
        if (resources == null) {
            return;
        }
        COSDictionary resDict = resources.getCOSObject();
        if (resDict == null || !visited.add(resDict)) {
            return;
        }
        for (COSName fontName : resources.getFontNames()) {
            try {
                PDFont font = resources.getFont(fontName);
                if (font != null && !font.isEmbedded()) {
                    resources.put(fontName, fallback);
                }
            } catch (IOException e) {
                log.warn("Could not load font {} for embedding check (non-critical, skipping slot): {}", fontName, e.getMessage());
            }
        }
        for (COSName xName : resources.getXObjectNames()) {
            try {
                PDXObject xo = resources.getXObject(xName);
                if (xo instanceof PDFormXObject form) {
                    fixFontsInResources(form.getResources(), fallback, visited);
                }
            } catch (IOException e) {
                log.warn("Could not load XObject {} for font recursion (non-critical): {}", xName, e.getMessage());
            }
        }
        for (COSName pName : resources.getPatternNames()) {
            try {
                PDAbstractPattern pat = resources.getPattern(pName);
                if (pat instanceof PDTilingPattern tiling) {
                    fixFontsInResources(tiling.getResources(), fallback, visited);
                }
            } catch (IOException e) {
                log.warn("Could not load pattern {} for font recursion (non-critical): {}", pName, e.getMessage());
            }
        }
    }

    private void processAppearanceForCid(PDAppearanceEntry entry, PDDocument doc, Set<COSDictionary> visited) {
        if (entry == null) {
            return;
        }
        if (entry.isStream()) {
            PDAppearanceStream s = entry.getAppearanceStream();
            if (s != null) {
                fixCidFontsInResources(s.getResources(), doc, visited);
            }
        } else if (entry.isSubDictionary()) {
            for (PDAppearanceStream s : entry.getSubDictionary().values()) {
                if (s != null) {
                    fixCidFontsInResources(s.getResources(), doc, visited);
                }
            }
        }
    }

    private void fixCidFontsInResources(PDResources resources, PDDocument doc, Set<COSDictionary> visited) {
        if (resources == null) {
            return;
        }
        COSDictionary resDict = resources.getCOSObject();
        if (resDict == null || !visited.add(resDict)) {
            return;
        }
        for (COSName fontName : resources.getFontNames()) {
            try {
                PDFont font = resources.getFont(fontName);
                if (font instanceof PDType0Font type0) {
                    PDCIDFont descendant = type0.getDescendantFont();
                    if (descendant instanceof PDCIDFontType2 cid2) {
                        ensureCidToGidMapIdentity(cid2);
                        ensureCidSet(cid2, doc);
                    }
                }
            } catch (IOException e) {
                log.warn("Could not load font {} for CID fix (non-critical): {}", fontName, e.getMessage());
            }
        }
        for (COSName xName : resources.getXObjectNames()) {
            try {
                PDXObject xo = resources.getXObject(xName);
                if (xo instanceof PDFormXObject form) {
                    fixCidFontsInResources(form.getResources(), doc, visited);
                }
            } catch (IOException e) {
                log.warn("Could not load XObject {} for CID font recursion (non-critical): {}", xName, e.getMessage());
            }
        }
        for (COSName pName : resources.getPatternNames()) {
            try {
                PDAbstractPattern pat = resources.getPattern(pName);
                if (pat instanceof PDTilingPattern tiling) {
                    fixCidFontsInResources(tiling.getResources(), doc, visited);
                }
            } catch (IOException e) {
                log.warn("Could not load pattern {} for CID font recursion (non-critical): {}", pName, e.getMessage());
            }
        }
    }

    private void ensureCidToGidMapIdentity(PDCIDFontType2 cid2) {
        // PDF/A: Type 2 CIDFonts must declare CID→GID mapping; Identity is valid when CID equals glyph index.
        COSDictionary cidDict = cid2.getCOSObject();
        if (!cidDict.containsKey(COSName.CID_TO_GID_MAP)) {
            cidDict.setItem(COSName.CID_TO_GID_MAP, COSName.IDENTITY);
        }
    }

    /**
     * Adds a {@code /CIDSet} stream to the font descriptor for embedded CIDFont subsets (PDF/A §6.3.5).
     * <p>
     * VeraPDF expects a bit stream: one bit per CID, packed in bytes; the most significant bit of the first byte
     * corresponds to CID 0 (PDF reference, CIDSet description). We enumerate CIDs 0..65535 and set bits where
     * {@link PDCIDFontType2#hasGlyph(int)} is true. For Identity CIDToGIDMap this matches glyphs present in the
     * embedded TrueType program well enough for validation on typical subset fonts.
     */
    private void ensureCidSet(PDCIDFontType2 cid2, PDDocument doc) {
        try {
            PDFontDescriptor fd = cid2.getFontDescriptor();
            if (fd == null || fd.getCIDSet() != null || !cid2.isEmbedded()) {
                return;
            }
            java.util.BitSet bits = new java.util.BitSet();
            int maxCid = -1;
            for (int cid = 0; cid < 65536; cid++) {
                try {
                    if (cid2.hasGlyph(cid)) {
                        bits.set(cid);
                        maxCid = Math.max(maxCid, cid);
                    }
                } catch (IOException e) {
                    log.warn("hasGlyph({}) stopped early while building CIDSet (non-critical, using CIDs 0..{}): {}", cid, maxCid, e.getMessage());
                    break;
                }
            }
            if (maxCid < 0) {
                return;
            }
            int byteLen = (maxCid / 8) + 1;
            byte[] cidSetBytes = new byte[byteLen];
            for (int i = 0; i <= maxCid; i++) {
                if (bits.get(i)) {
                    int byteIndex = i / 8;
                    int bitIndex = 7 - (i % 8);
                    cidSetBytes[byteIndex] |= (byte) (1 << bitIndex);
                }
            }
            PDStream cidSetStream = new PDStream(doc);
            try (OutputStream os = cidSetStream.createOutputStream()) {
                os.write(cidSetBytes);
            }
            fd.setCIDSet(cidSetStream);
        } catch (Exception e) {
            log.warn("Could not add CIDSet stream (non-critical; VeraPDF may still fail §6.3.5): {}", e.getMessage());
        }
    }
}
