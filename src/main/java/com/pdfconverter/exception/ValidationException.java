package com.pdfconverter.exception;

import com.pdfconverter.model.ValidationReport;

/**
 * Thrown when conversion produces a PDF that fails PDF/A validation.
 */
public class ValidationException extends RuntimeException {

    private final ValidationReport validationReport;

    public ValidationException(String message, ValidationReport validationReport) {
        super(message);
        this.validationReport = validationReport;
    }

    public ValidationReport getValidationReport() {
        return validationReport;
    }
}
