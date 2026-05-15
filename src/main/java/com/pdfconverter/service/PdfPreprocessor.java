package com.pdfconverter.service;

import com.pdfconverter.exception.ConversionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDDocumentCatalogAdditionalActions;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-processes a PDF to strip features that are prohibited by PDF/A-1b:
 * <ul>
 *   <li>Encrypted documents are rejected immediately</li>
 *   <li>JavaScript actions are removed</li>
 *   <li>AcroForm fields are flattened</li>
 *   <li>Interactive annotations are removed</li>
 *   <li>Embedded file attachments are removed</li>
 * </ul>
 */
@Service
public class PdfPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(PdfPreprocessor.class);

    /**
     * Loads, preprocesses, and saves the PDF.
     *
     * @param inputBytes source PDF
     * @param warnings   mutable list to collect non-fatal warnings
     * @return preprocessed PDF byte array
     * @throws ConversionException if the PDF is encrypted or cannot be read
     */
    public byte[] preprocess(byte[] inputBytes, List<String> warnings) throws IOException {
        // Check for encryption before fully loading
        try (PDDocument doc = Loader.loadPDF(inputBytes)) {
            if (doc.isEncrypted()) {
                throw new ConversionException(
                        "PDF is encrypted and cannot be processed");
            }

            removeJavaScript(doc, warnings);
            flattenForms(doc, warnings);
            removeProhibitedAnnotations(doc, warnings);
            removeEmbeddedFiles(doc, warnings);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos, CompressParameters.NO_COMPRESSION);
            log.info("Preprocessed PDF ({} bytes) → {} bytes", inputBytes.length, baos.size());
            return baos.toByteArray();
        }
    }

    private void removeJavaScript(PDDocument doc, List<String> warnings) {
        try {
            // Document-level open action
            var openAction = doc.getDocumentCatalog().getOpenAction();
            if (openAction instanceof PDActionJavaScript) {
                doc.getDocumentCatalog().setOpenAction(null);
                warnings.add("Removed document-level JavaScript open action");
            }

            // Document additional actions (AA dictionary)
            PDDocumentCatalogAdditionalActions aa = doc.getDocumentCatalog().getActions();
            if (aa != null) {
                var cosAA = aa.getCOSObject();
                if (cosAA.size() > 0) {
                    cosAA.clear();
                    warnings.add("Removed document additional actions (AA)");
                }
            }

            // Page-level actions
            int removed = 0;
            for (PDPage page : doc.getPages()) {
                if (page.getActions() != null) {
                    page.getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.AA);
                    removed++;
                }
            }
            if (removed > 0) {
                warnings.add("Removed page-level actions from " + removed + " page(s)");
            }
        } catch (Exception e) {
            log.warn("Could not fully remove JavaScript actions: {}", e.getMessage());
            warnings.add("Warning: could not fully remove JavaScript actions: " + e.getMessage());
        }
    }

    private void flattenForms(PDDocument doc, List<String> warnings) {
        try {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm != null && !acroForm.getFields().isEmpty()) {
                acroForm.flatten();
                warnings.add("Flattened AcroForm with " + acroForm.getFields().size() + " field(s)");
            }
        } catch (Exception e) {
            log.warn("Could not flatten AcroForm: {}", e.getMessage());
            warnings.add("Warning: could not flatten form fields: " + e.getMessage());
        }
    }

    private void removeProhibitedAnnotations(PDDocument doc, List<String> warnings) {
        int totalRemoved = 0;
        for (PDPage page : doc.getPages()) {
            try {
                List<PDAnnotation> annotations = page.getAnnotations();
                if (annotations == null || annotations.isEmpty()) continue;

                List<PDAnnotation> toKeep = new ArrayList<>();
                for (PDAnnotation ann : annotations) {
                    String subtype = ann.getSubtype();
                    // Keep only PrintMark and TrapNet — all others are prohibited in PDF/A-1b
                    if ("PrintMark".equals(subtype) || "TrapNet".equals(subtype)) {
                        toKeep.add(ann);
                    }
                    // Widget annotations (form fields) are handled by acroForm.flatten()
                }
                int removed = annotations.size() - toKeep.size();
                if (removed > 0) {
                    page.setAnnotations(toKeep);
                    totalRemoved += removed;
                }
            } catch (Exception e) {
                log.warn("Could not process annotations on a page: {}", e.getMessage());
                warnings.add("Warning: could not remove some annotations: " + e.getMessage());
            }
        }
        if (totalRemoved > 0) {
            warnings.add("Removed " + totalRemoved + " interactive annotation(s)");
        }
    }

    private void removeEmbeddedFiles(PDDocument doc, List<String> warnings) {
        try {
            var names = doc.getDocumentCatalog().getNames();
            if (names != null) {
                var cosNames = names.getCOSObject();
                if (cosNames.containsKey(org.apache.pdfbox.cos.COSName.getPDFName("EmbeddedFiles"))) {
                    cosNames.removeItem(org.apache.pdfbox.cos.COSName.getPDFName("EmbeddedFiles"));
                    warnings.add("Removed embedded file attachments");
                }
            }
        } catch (Exception e) {
            log.warn("Could not remove embedded files: {}", e.getMessage());
            warnings.add("Warning: could not remove embedded files: " + e.getMessage());
        }
    }
}
