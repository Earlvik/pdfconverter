package com.pdfconverter.model;

/**
 * Target PDF/A conformance level.
 */
public enum PdfAFlavor {

    PDFA_1A(1, "a"),
    PDFA_1B(1, "b");

    private final int part;
    private final String conformance;

    PdfAFlavor(int part, String conformance) {
        this.part = part;
        this.conformance = conformance;
    }

    public int getPart() {
        return part;
    }

    public String getConformance() {
        return conformance;
    }

    public String getDisplayName() {
        return "PDF/A-" + part + conformance;
    }
}
