package com.pdfconverter.model;

import java.util.Collections;
import java.util.List;

/**
 * Result of VeraPDF validation against a PDF/A profile.
 */
public class ValidationReport {

    private final boolean compliant;
    private final String profileName;
    private final int totalChecks;
    private final int passedChecks;
    private final int failedChecks;
    private final List<ValidationFailure> failures;

    public ValidationReport(boolean compliant, String profileName,
                            int totalChecks, int passedChecks, int failedChecks,
                            List<ValidationFailure> failures) {
        this.compliant = compliant;
        this.profileName = profileName;
        this.totalChecks = totalChecks;
        this.passedChecks = passedChecks;
        this.failedChecks = failedChecks;
        this.failures = failures != null ? List.copyOf(failures) : Collections.emptyList();
    }

    public boolean isCompliant() {
        return compliant;
    }

    public String getProfileName() {
        return profileName;
    }

    public int getTotalChecks() {
        return totalChecks;
    }

    public int getPassedChecks() {
        return passedChecks;
    }

    public int getFailedChecks() {
        return failedChecks;
    }

    public List<ValidationFailure> getFailures() {
        return failures;
    }

    @Override
    public String toString() {
        return String.format("ValidationReport{compliant=%s, profile='%s', passed=%d/%d, failures=%d}",
                compliant, profileName, passedChecks, totalChecks, failedChecks);
    }
}
