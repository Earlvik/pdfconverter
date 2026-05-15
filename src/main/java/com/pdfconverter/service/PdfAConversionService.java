package com.pdfconverter.service;

import com.pdfconverter.config.PdfConverterConfig;
import com.pdfconverter.exception.ConversionException;
import com.pdfconverter.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Main orchestrator for PDF to PDF/A conversion.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Pre-process (strip prohibited features; reject encrypted PDFs)</li>
 *   <li>Strategy 1 — Structural Fix (in-place metadata + Output Intent)</li>
 *   <li>Validate with VeraPDF</li>
 *   <li>If not compliant, Strategy 2 — Render &amp; Rebuild</li>
 *   <li>Validate again; return result with report</li>
 * </ol>
 */
@Service
public class PdfAConversionService {

    private static final Logger log = LoggerFactory.getLogger(PdfAConversionService.class);

    private final PdfPreprocessor preprocessor;
    private final StructuralConverter structuralConverter;
    private final RenderConverter renderConverter;
    private final VeraPdfValidator validator;
    private final PdfConverterConfig config;
    
    @org.springframework.beans.factory.annotation.Value("${pdf-converter.strategy2.enabled:true}")
    private boolean strategy2Enabled;

    public PdfAConversionService(PdfPreprocessor preprocessor,
                                 StructuralConverter structuralConverter,
                                 RenderConverter renderConverter,
                                 VeraPdfValidator validator,
                                 PdfConverterConfig config) {
        this.preprocessor = preprocessor;
        this.structuralConverter = structuralConverter;
        this.renderConverter = renderConverter;
        this.validator = validator;
        this.config = config;
    }

    /**
     * Converts a PDF to PDF/A-1b (or the flavor specified in the request).
     *
     * @param request conversion parameters
     * @return {@link ConversionResult} with success status, strategy used, and validation report
     * @throws ConversionException if the input is invalid or unreadable
     */
    public ConversionResult convert(ConversionRequest request) {
        long startMs = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        validateRequest(request);

        PdfAFlavor flavor = request.getFlavor();
        log.info("Starting PDF/A conversion ({} bytes) → ({})",
                request.getInputBytes().length,
                flavor.getDisplayName());

        byte[] currentBytes = request.getInputBytes();

        long validationMs = 0;
        long preprocessMs = 0;
        long structuralMs = 0;
        long renderMs = 0;

        try {
            // --- 0. Validation First ---
            long t0 = System.currentTimeMillis();
            ValidationReport initialReport = validator.validate(currentBytes, flavor);
            validationMs += System.currentTimeMillis() - t0;
            if (initialReport.isCompliant()) {
                log.info("Input PDF is already compliant with {}. Skipping conversion.", flavor.getDisplayName());
                long elapsed = System.currentTimeMillis() - startMs;
                log.info("Conversion timing — total: {} ms (validation only: {} ms)", elapsed, validationMs);
                return ConversionResult.builder()
                        .success(true)
                        .outputBytes(currentBytes)
                        .flavor(flavor)
                        .strategyUsed(null) // no strategy needed
                        .validationReport(initialReport)
                        .warnings(warnings)
                        .processingTimeMs(elapsed)
                        .build();
            }

            // --- 1. Pre-process ---
            t0 = System.currentTimeMillis();
            byte[] preprocessedBytes = preprocessor.preprocess(currentBytes, warnings);
            preprocessMs = System.currentTimeMillis() - t0;

            // --- Strategy 1: Structural ---
            ConversionStrategy preferredStrategy = request.getPreferredStrategy();
            boolean skipStructural = preferredStrategy == ConversionStrategy.RENDER;

            ValidationReport report = null;
            byte[] candidateBytes = null;
            ConversionStrategy usedStrategy = null;

            if (!skipStructural) {
                try {
                    t0 = System.currentTimeMillis();
                    byte[] structuralBytes = structuralConverter.convert(preprocessedBytes, flavor, warnings);
                    structuralMs = System.currentTimeMillis() - t0;
                    t0 = System.currentTimeMillis();
                    report = validator.validate(structuralBytes, flavor);
                    validationMs += System.currentTimeMillis() - t0;

                    if (report.isCompliant()) {
                        candidateBytes = structuralBytes;
                        usedStrategy = ConversionStrategy.STRUCTURAL;
                        log.info("Strategy 1 (Structural) succeeded — PDF/A validation passed");
                    } else {
                        log.info("Strategy 1 (Structural) produced non-compliant PDF ({} failures), " +
                                "falling back to Strategy 2 (Render)", report.getFailedChecks());
                        report.getFailures().stream().limit(5).forEach(f ->
                                log.warn("  STRATEGY 1 FAILURE: [{}] {}", f.getRuleId(), f.getDescription()));
                        warnings.add("Structural conversion failed validation (" + report.getFailedChecks() +
                                " failures); used render fallback");
                    }
                } catch (Exception e) {
                    log.warn("Strategy 1 (Structural) threw an exception: {} — falling back to Render", e.getMessage());
                    warnings.add("Structural conversion error: " + e.getMessage() + "; used render fallback");
                }
            }

            // --- Strategy 2: Render & Rebuild (fallback or forced) ---
            if (candidateBytes == null) {
                if (strategy2Enabled) {
                    t0 = System.currentTimeMillis();
                    byte[] renderBytes = renderConverter.convert(
                            preprocessedBytes, flavor, config.getRenderDpi(), warnings);
                    renderMs = System.currentTimeMillis() - t0;
                    t0 = System.currentTimeMillis();
                    report = validator.validate(renderBytes, flavor);
                    validationMs += System.currentTimeMillis() - t0;
                    candidateBytes = renderBytes;
                    usedStrategy = ConversionStrategy.RENDER;

                    if (report.isCompliant()) {
                        log.info("Strategy 2 (Render) succeeded — PDF/A validation passed");
                    } else {
                        log.warn("Strategy 2 (Render) also failed validation ({} failures)",
                                report.getFailedChecks());
                    }
                } else {
                    log.warn("Strategy 2 (Render) is disabled by configuration. Conversion failed.");
                    candidateBytes = preprocessedBytes;
                }
            }

            if (report == null && candidateBytes != null) {
                t0 = System.currentTimeMillis();
                report = validator.validate(candidateBytes, flavor);
                validationMs += System.currentTimeMillis() - t0;
            }

            long elapsed = System.currentTimeMillis() - startMs;
            boolean success = report != null && report.isCompliant();
            log.info("Conversion complete — total: {} ms | phases: preprocess={} ms, structural={} ms, validation(sum)={} ms, render={} ms | success={}, strategy={}",
                    elapsed, preprocessMs, structuralMs, validationMs, renderMs,
                    success, usedStrategy);

            return ConversionResult.builder()
                    .success(success)
                    .outputBytes(candidateBytes)
                    .flavor(flavor)
                    .strategyUsed(usedStrategy)
                    .validationReport(report)
                    .warnings(warnings)
                    .processingTimeMs(elapsed)
                    .build();

        } catch (ConversionException ce) {
            throw ce; // re-throw as-is (e.g. encrypted PDF)
        } catch (IOException e) {
            throw new ConversionException("I/O error during conversion: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------

    private void validateRequest(ConversionRequest request) {
        byte[] input = request.getInputBytes();
        if (input == null || input.length == 0) {
            throw new ConversionException("Input byte array is empty or null");
        }
    }
}
