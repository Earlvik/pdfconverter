package com.pdfconverter.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of a PDF to PDF/A conversion, including validation report.
 */
public class ConversionResult {

    private final boolean success;
    private final byte[] outputBytes;
    private final PdfAFlavor flavor;
    private final ConversionStrategy strategyUsed;
    private final ValidationReport validationReport;
    private final List<String> warnings;
    private final long processingTimeMs;

    private ConversionResult(Builder builder) {
        this.success = builder.success;
        this.outputBytes = builder.outputBytes;
        this.flavor = builder.flavor;
        this.strategyUsed = builder.strategyUsed;
        this.validationReport = builder.validationReport;
        this.warnings = builder.warnings != null
                ? Collections.unmodifiableList(new ArrayList<>(builder.warnings))
                : Collections.emptyList();
        this.processingTimeMs = builder.processingTimeMs;
    }

    public boolean isSuccess() {
        return success;
    }

    public byte[] getOutputBytes() {
        return outputBytes;
    }

    public PdfAFlavor getFlavor() {
        return flavor;
    }

    public ConversionStrategy getStrategyUsed() {
        return strategyUsed;
    }

    public ValidationReport getValidationReport() {
        return validationReport;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public long getProcessingTimeMs() {
        return processingTimeMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return String.format("ConversionResult{success=%s, strategy=%s, flavor=%s, time=%dms, warnings=%d}",
                success, strategyUsed, flavor != null ? flavor.getDisplayName() : "null",
                processingTimeMs, warnings.size());
    }

    public static class Builder {
        private boolean success;
        private byte[] outputBytes;
        private PdfAFlavor flavor;
        private ConversionStrategy strategyUsed;
        private ValidationReport validationReport;
        private List<String> warnings = new ArrayList<>();
        private long processingTimeMs;

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder outputBytes(byte[] outputBytes) {
            this.outputBytes = outputBytes;
            return this;
        }

        public Builder flavor(PdfAFlavor flavor) {
            this.flavor = flavor;
            return this;
        }

        public Builder strategyUsed(ConversionStrategy strategyUsed) {
            this.strategyUsed = strategyUsed;
            return this;
        }

        public Builder validationReport(ValidationReport validationReport) {
            this.validationReport = validationReport;
            return this;
        }

        public Builder warnings(List<String> warnings) {
            this.warnings = warnings;
            return this;
        }

        public Builder addWarning(String warning) {
            this.warnings.add(warning);
            return this;
        }

        public Builder processingTimeMs(long processingTimeMs) {
            this.processingTimeMs = processingTimeMs;
            return this;
        }

        public ConversionResult build() {
            return new ConversionResult(this);
        }
    }
}
