package com.pdfconverter;

import com.pdfconverter.model.ConversionRequest;
import com.pdfconverter.model.ConversionResult;
import com.pdfconverter.model.PdfAFlavor;
import com.pdfconverter.service.PdfAConversionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Integration test that converts every sample PDF to PDF/A-1b and validates the result.
 * Skips encrypted PDFs. Reports per-file pass/fail to the console.
 */
@SpringBootTest
class SampleFilesIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SampleFilesIntegrationTest.class);

    /** Relative to the project root. Adjust if running from a different working directory. */
    private static final Path SAMPLE_DIR = Paths.get("sample-files");
    private static final Path OUTPUT_DIR = Paths.get("target", "pdfa-output");

    @Autowired
    private PdfAConversionService conversionService;

    @BeforeAll
    static void prepareOutputDir() throws IOException {
        Files.createDirectories(OUTPUT_DIR);
    }

    @TestFactory
    @DisplayName("Convert all sample PDFs to PDF/A-1b")
    Stream<DynamicTest> convertSampleFiles() throws IOException {
        List<Path> pdfFiles = discoverSamplePdfs();
        assertFalse(pdfFiles.isEmpty(), "No PDF files found in " + SAMPLE_DIR.toAbsolutePath());

        log.info("Found {} PDF files to convert", pdfFiles.size());

        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        return pdfFiles.stream().map(pdfPath -> dynamicTest(
                pdfPath.getParent().getFileName() + "/" + pdfPath.getFileName(),
                () -> runConversionTest(pdfPath, passed, failed, skipped)
        ));
    }

    private void runConversionTest(Path pdfPath,
                                   List<String> passed,
                                   List<String> failed,
                                   List<String> skipped) {
        String label = pdfPath.getParent().getFileName() + "/" + pdfPath.getFileName();

        // Build output path
        String outputFileName = pdfPath.getFileName().toString()
                .replaceAll("(?i)\\.pdf$", "-pdfa1b.pdf");
        Path outputPath = OUTPUT_DIR
                .resolve(pdfPath.getParent().getFileName().toString())
                .resolve(outputFileName);

        try {
            Files.createDirectories(outputPath.getParent());
        } catch (IOException e) {
            fail("Could not create output directory: " + e.getMessage());
        }

        byte[] inputBytes;
        try {
            inputBytes = Files.readAllBytes(pdfPath);
        } catch (IOException e) {
            fail("Could not read input file: " + e.getMessage());
            return;
        }

        ConversionRequest request = ConversionRequest
                .builder(inputBytes)
                .flavor(PdfAFlavor.PDFA_1B)
                .build();

        ConversionResult result;
        try {
            result = conversionService.convert(request);
        } catch (com.pdfconverter.exception.ConversionException ce) {
            // Encrypted PDFs throw this — expected
            if (ce.getMessage() != null && ce.getMessage().contains("encrypted")) {
                skipped.add(label);
                log.info("[SKIPPED] {} — encrypted PDF", label);
                return; // test passes (expected skip)
            }
            failed.add(label);
            log.error("[FAILED]  {} — ConversionException: {}", label, ce.getMessage());
            fail("Conversion failed with exception: " + ce.getMessage());
            return;
        } catch (Exception e) {
            failed.add(label);
            log.error("[FAILED]  {} — unexpected exception: {}", label, e.getMessage());
            fail("Unexpected exception: " + e.getMessage());
            return;
        }

        // Log warnings
        result.getWarnings().forEach(w -> log.debug("  WARNING: {}", w));

        String status = result.isSuccess() ? "[PASSED] " : "[FAILED] ";
        log.info("{} {} | strategy={} | time={}ms | validation={}/{} checks passed",
                status, label,
                result.getStrategyUsed(),
                result.getProcessingTimeMs(),
                result.getValidationReport().getPassedChecks(),
                result.getValidationReport().getTotalChecks());

        if (!result.isSuccess()) {
            result.getValidationReport().getFailures().stream().limit(5).forEach(f ->
                    log.warn("  FAILURE: [{}] {}", f.getRuleId(), f.getDescription()));
        }

        if (result.isSuccess()) {
            passed.add(label);
            if (result.getOutputBytes() != null) {
                try {
                    Files.write(outputPath, result.getOutputBytes());
                } catch (IOException e) {
                    fail("Could not write output file: " + e.getMessage());
                }
            }
        } else {
            failed.add(label);
        }

        assertTrue(result.isSuccess(),
                String.format("PDF/A validation failed for '%s' (%d failures). First failure: %s",
                        label,
                        result.getValidationReport().getFailedChecks(),
                        result.getValidationReport().getFailures().isEmpty() ? "none" :
                                result.getValidationReport().getFailures().get(0)));
    }

    private List<Path> discoverSamplePdfs() throws IOException {
        if (!Files.exists(SAMPLE_DIR)) {
            log.warn("Sample directory does not exist: {}", SAMPLE_DIR.toAbsolutePath());
            return List.of();
        }
        try (Stream<Path> walker = Files.walk(SAMPLE_DIR)) {
            return walker
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                    .sorted()
                    .toList();
        }
    }
}
