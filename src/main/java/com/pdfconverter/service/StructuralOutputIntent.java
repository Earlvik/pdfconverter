package com.pdfconverter.service;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Adds a single PDF/A-1 output intent (sRGB vs bundled CMYK) after ICC repair, based on document color usage.
 */
final class StructuralOutputIntent {

    private static final Logger log = LoggerFactory.getLogger(StructuralOutputIntent.class);

    private final StructuralCmykDetection cmyk;
    private final StructuralRgbDetection rgb;

    StructuralOutputIntent(StructuralCmykDetection cmyk, StructuralRgbDetection rgb) {
        this.cmyk = cmyk;
        this.rgb = rgb;
    }

    void add(PDDocument doc, List<String> warnings) {
        try {
            PDDocumentCatalog catalog = doc.getDocumentCatalog();

            catalog.getCOSObject().removeItem(COSName.getPDFName("OutputIntents"));

            boolean isCmyk = needsCmykOutputIntent(doc);

            InputStream iccStream;
            String destOutputProfileName;
            int numComponents;

            if (isCmyk) {
                iccStream = StructuralOutputIntent.class.getResourceAsStream(StructuralBundledResources.ICC_DEFAULT_CMYK);
                destOutputProfileName = "CGATS TR 001";
                numComponents = 4;
            } else {
                byte[] iccBytes = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
                iccStream = new ByteArrayInputStream(iccBytes);
                destOutputProfileName = "sRGB IEC61966-2.1";
                numComponents = 3;
            }

            try (InputStream is = iccStream) {
                if (is == null) {
                    throw new IOException("ICC Profile not found");
                }
                PDOutputIntent oi = new PDOutputIntent(doc, is);
                oi.setInfo(destOutputProfileName);
                oi.setOutputCondition(destOutputProfileName);
                oi.setOutputConditionIdentifier(destOutputProfileName);
                oi.setRegistryName("http://www.color.org");
                oi.getCOSObject().setName(COSName.S, "GTS_PDFA1");

                COSBase profileStream = oi.getCOSObject().getDictionaryObject(COSName.DEST_OUTPUT_PROFILE);
                if (profileStream instanceof COSStream cosStream) {
                    cosStream.setInt(COSName.N, numComponents);
                }

                catalog.addOutputIntent(oi);
                log.debug("Added {} Output Intent", isCmyk ? "CMYK" : "sRGB");
            }
        } catch (Exception e) {
            log.warn("Failed to add Output Intent: {}", e.getMessage());
            warnings.add("Warning: could not add Output Intent: " + e.getMessage());
        }
    }

    /**
     * PDF/A-1 allows a single output intent. Prefer CMYK profile only when CMYK is used without device RGB.
     */
    private boolean needsCmykOutputIntent(PDDocument doc) {
        return cmyk.documentUsesDeviceCmyk(doc) && !rgb.documentUsesDeviceRgb(doc);
    }
}
