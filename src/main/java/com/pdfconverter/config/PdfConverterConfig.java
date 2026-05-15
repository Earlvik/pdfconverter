package com.pdfconverter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the PDF converter library.
 */
@Configuration
@ConfigurationProperties(prefix = "pdf-converter")
public class PdfConverterConfig {

    private String defaultFlavor = "PDFA_1B";
    private int renderDpi = 300;
    private int timeoutSeconds = 120;
    private boolean validateAfterConversion = true;
    private int maxValidationFailuresReported = 50;

    public String getDefaultFlavor() {
        return defaultFlavor;
    }

    public void setDefaultFlavor(String defaultFlavor) {
        this.defaultFlavor = defaultFlavor;
    }

    public int getRenderDpi() {
        return renderDpi;
    }

    public void setRenderDpi(int renderDpi) {
        this.renderDpi = renderDpi;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isValidateAfterConversion() {
        return validateAfterConversion;
    }

    public void setValidateAfterConversion(boolean validateAfterConversion) {
        this.validateAfterConversion = validateAfterConversion;
    }

    public int getMaxValidationFailuresReported() {
        return maxValidationFailuresReported;
    }

    public void setMaxValidationFailuresReported(int maxValidationFailuresReported) {
        this.maxValidationFailuresReported = maxValidationFailuresReported;
    }
}
