package com.pdfconverter.service;

import com.pdfconverter.model.PdfAFlavor;
import com.pdfconverter.model.ValidationFailure;
import com.pdfconverter.model.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.verapdf.core.ValidationException;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;

import jakarta.annotation.PostConstruct;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates a PDF against a PDF/A profile using VeraPDF (Greenfield implementation).
 */
@Service
public class VeraPdfValidator {

    private static final Logger log = LoggerFactory.getLogger(VeraPdfValidator.class);

    @PostConstruct
    public void init() {
        VeraGreenfieldFoundryProvider.initialise();
        log.info("VeraPDF Greenfield foundry initialised");
    }

    /**
     * Validates the PDF byte array against the specified PDF/A flavor.
     *
     * @param pdfBytes byte array of the PDF to validate
     * @param flavor   PDF/A flavor to validate against
     * @return a {@link ValidationReport} with compliance status and any failures
     */
    public ValidationReport validate(byte[] pdfBytes, PdfAFlavor flavor) throws IOException {
        PDFAFlavour veraFlavour = toVeraFlavour(flavor);
        log.debug("Validating PDF byte array ({} bytes) against {}", pdfBytes.length, veraFlavour);

        try (InputStream is = new ByteArrayInputStream(pdfBytes);
             PDFAParser parser = Foundries.defaultInstance().createParser(is, veraFlavour)) {

            PDFAValidator validator = Foundries.defaultInstance().createValidator(veraFlavour, false);
            ValidationResult result = validator.validate(parser);

            return buildReport(result, flavor);

        } catch (Exception e) {
            log.warn("VeraPDF threw a validation exception: {}", e.getMessage());
            // Return a non-compliant report with the error
            List<ValidationFailure> failures = List.of(
                    new ValidationFailure("EXCEPTION", e.getMessage(), "N/A"));
            return new ValidationReport(false, flavor.getDisplayName(), 0, 0, 1, failures);
        }
    }

    private ValidationReport buildReport(ValidationResult result, PdfAFlavor flavor) {
        boolean compliant = result.isCompliant();
        int totalChecks = result.getTotalAssertions();
        List<ValidationFailure> failures = new ArrayList<>();

        for (TestAssertion assertion : result.getTestAssertions()) {
            if (assertion.getStatus() == TestAssertion.Status.FAILED) {
                String ruleId = assertion.getRuleId() != null
                        ? assertion.getRuleId().toString() : "UNKNOWN";
                String description = assertion.getMessage() != null
                        ? assertion.getMessage() : "(no description)";
                String location = assertion.getLocationContext() != null
                        ? assertion.getLocationContext().toString() : "N/A";
                failures.add(new ValidationFailure(ruleId, description, location));
            }
        }

        int failedChecks = failures.size();
        int passedChecks = totalChecks - failedChecks;

        ValidationReport report = new ValidationReport(
                compliant, flavor.getDisplayName(), totalChecks, passedChecks, failedChecks, failures);

        log.info("Validation result: compliant={}, {}/{} checks passed, {} failure(s)",
                compliant, passedChecks, totalChecks, failedChecks);
        return report;
    }

    private PDFAFlavour toVeraFlavour(PdfAFlavor flavor) {
        return switch (flavor) {
            case PDFA_1A -> PDFAFlavour.PDFA_1_A;
            case PDFA_1B -> PDFAFlavour.PDFA_1_B;
        };
    }
}
