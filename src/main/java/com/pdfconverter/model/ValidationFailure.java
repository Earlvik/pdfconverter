package com.pdfconverter.model;

/**
 * A single PDF/A validation failure from VeraPDF.
 */
public class ValidationFailure {

    private final String ruleId;
    private final String description;
    private final String location;

    public ValidationFailure(String ruleId, String description, String location) {
        this.ruleId = ruleId;
        this.description = description;
        this.location = location;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getDescription() {
        return description;
    }

    public String getLocation() {
        return location;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s (at %s)", ruleId, description, location);
    }
}
