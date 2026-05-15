package com.pdfconverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;

import java.io.File;

public class TestOI {
    public static void main(String[] args) throws Exception {
        File f = new File("target/pdfa-output/001-trivial/minimal-document-pdfa1b.pdf");
        if (!f.exists()) {
            System.out.println("File not found: " + f.getAbsolutePath());
            return;
        }
        try (PDDocument doc = Loader.loadPDF(f)) {
            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            for (PDOutputIntent oi : catalog.getOutputIntents()) {
                System.out.println("OI Dictionary:");
                for (java.util.Map.Entry<org.apache.pdfbox.cos.COSName, org.apache.pdfbox.cos.COSBase> entry : oi.getCOSObject().entrySet()) {
                    System.out.println("  " + entry.getKey().getName() + " = " + entry.getValue());
                }
                
                org.apache.pdfbox.cos.COSStream stream = (org.apache.pdfbox.cos.COSStream) oi.getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.DEST_OUTPUT_PROFILE);
                if (stream != null) {
                    System.out.println("DestOutputProfile Stream Dictionary:");
                    for (java.util.Map.Entry<org.apache.pdfbox.cos.COSName, org.apache.pdfbox.cos.COSBase> entry : stream.entrySet()) {
                        System.out.println("  " + entry.getKey().getName() + " = " + entry.getValue());
                    }
                } else {
                    System.out.println("DestOutputProfile is NULL!");
                }
            }
        }
    }
}
