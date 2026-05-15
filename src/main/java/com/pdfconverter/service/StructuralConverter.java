package com.pdfconverter.service;

import com.pdfconverter.model.PdfAFlavor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.AdobePDFSchema;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.schema.XMPBasicSchema;
import org.apache.xmpbox.xml.XmpSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.List;

/**
 * Strategy 1: Structural converter.
 * <p>
 * Attempts to fix an already-preprocessed PDF in-place to be PDF/A-1b compliant by:
 * <ul>
 *   <li>Adding a proper PDF/A XMP metadata block</li>
 *   <li>Adding an sRGB or CMYK Output Intent (ICC profiles per {@link StructuralBundledResources})</li>
 *   <li>Ensuring document version is 1.4</li>
 * </ul>
 * Text content and vector graphics are preserved.
 */
@Service
public class StructuralConverter {

    private static final Logger log = LoggerFactory.getLogger(StructuralConverter.class);

    private final StructuralCmykDetection cmykDetection = new StructuralCmykDetection();
    private final StructuralRgbDetection rgbDetection = new StructuralRgbDetection();
    private final StructuralIccRepair iccRepair = new StructuralIccRepair();
    private final StructuralOutputIntent outputIntent = new StructuralOutputIntent(cmykDetection, rgbDetection);
    private final StructuralTransparencyStripper transparencyStripper = new StructuralTransparencyStripper();
    private final StructuralFontFixes fontFixes = new StructuralFontFixes();
    private final StructuralStreamSanitizer streamSanitizer = new StructuralStreamSanitizer();

    /**
     * Applies PDF/A-1b structural fixes.
     * <p>
     * Order matters: XMP and Info stripping first; font fixes before content-dependent passes;
     * ICC repair before OutputIntent; transparency strip last so all XObjects exist.
     *
     * @param preprocessedBytes bytes from PdfPreprocessor
     * @param flavor           target PDF/A flavor
     * @param warnings         mutable warning list
     * @return structurally fixed byte array
     */
    public byte[] convert(byte[] preprocessedBytes, PdfAFlavor flavor, List<String> warnings) throws IOException {
        try (PDDocument doc = Loader.loadPDF(preprocessedBytes)) {
            doc.getDocument().setVersion(1.4f);

            // Metadata: PDF/A XMP + remove Info keys that §6.7.3 would compare to XMP
            addPdfaXmpMetadata(doc, flavor, warnings);

            // Fonts: embed fallback for missing subset; CID fonts get Identity + CIDSet where needed
            fontFixes.fixFonts(doc);
            fontFixes.fixCidFonts(doc);

            // Streams: VeraPDF string length; LZW → Flate for PDF/A-1
            streamSanitizer.fixLzwAndStrings(doc);

            // Colour: normalize ICC profile streams; then single OutputIntent (sRGB vs CMYK)
            iccRepair.fixIccBasedStreams(doc);
            outputIntent.add(doc, warnings);

            // Interactive and transparency rules
            sanitiseInteractiveElements(doc);
            transparencyStripper.stripTransparency(doc);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos, CompressParameters.NO_COMPRESSION);
            log.info("Structural conversion complete → {} bytes", baos.size());
            return baos.toByteArray();
        }
    }

    private void addPdfaXmpMetadata(PDDocument doc, PdfAFlavor flavor, List<String> warnings) {
        try {
            XMPMetadata xmp = XMPMetadata.createXMPMetadata();

            PDFAIdentificationSchema pdfaId = xmp.createAndAddPDFAIdentificationSchema();
            pdfaId.setPart(flavor.getPart());
            pdfaId.setConformance(flavor.getConformance().toUpperCase());

            DublinCoreSchema dc = xmp.createAndAddDublinCoreSchema();

            org.apache.pdfbox.pdmodel.PDDocumentInformation info = doc.getDocumentInformation();
            if (info == null) {
                info = new org.apache.pdfbox.pdmodel.PDDocumentInformation();
                doc.setDocumentInformation(info);
            }

            String title = info.getTitle();
            if (title == null || title.trim().isEmpty()) {
                title = "PDF/A Document";
                info.setTitle(title);
            }
            dc.setTitle(sanitizeXmlString(title));

            String subject = info.getSubject();
            if (subject != null && !subject.trim().isEmpty()) {
                dc.setDescription(sanitizeXmlString(subject));
            }

            String author = info.getAuthor();
            if (author != null && !author.trim().isEmpty()) {
                dc.addCreator(sanitizeXmlString(author));
            }

            XMPBasicSchema basic = xmp.createAndAddXMPBasicSchema();

            Calendar createDate = info.getCreationDate();
            if (createDate == null) {
                createDate = Calendar.getInstance();
                info.setCreationDate(createDate);
            }
            basic.setCreateDate(createDate);

            Calendar modifyDate = info.getModificationDate();
            if (modifyDate == null) {
                modifyDate = Calendar.getInstance();
                info.setModificationDate(modifyDate);
            }
            basic.setModifyDate(modifyDate);

            String creator = info.getCreator();
            if (creator == null || creator.trim().isEmpty()) {
                creator = "PDF Converter Library";
                info.setCreator(creator);
            }
            basic.setCreatorTool(sanitizeXmlString(creator));

            AdobePDFSchema adobePdf = xmp.createAndAddAdobePDFSchema();
            String producer = info.getProducer();
            if (producer == null || producer.trim().isEmpty()) {
                producer = "PDF Converter Library";
                info.setProducer(producer);
            }
            adobePdf.setProducer(sanitizeXmlString(producer));

            String keywords = info.getKeywords();
            if (keywords != null && !keywords.trim().isEmpty()) {
                adobePdf.setKeywords(sanitizeXmlString(keywords));
            }

            XmpSerializer serializer = new XmpSerializer();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            serializer.serialize(xmp, baos, true);

            PDMetadata metadata = new PDMetadata(doc);
            metadata.importXMPMetadata(baos.toByteArray());
            doc.getDocumentCatalog().setMetadata(metadata);

            stripDocumentInfoPairedWithXmp(doc);

            log.debug("Added PDF/A XMP metadata ({}-{})", flavor.getPart(), flavor.getConformance());
        } catch (Exception e) {
            log.warn("Failed to set XMP metadata: {}", e.getMessage());
            warnings.add("Warning: could not set PDF/A XMP metadata: " + e.getMessage());
        }
    }

    /**
     * Removes document Info entries that ISO 19005-1 §6.7.3 requires to match XMP when present.
     * Values remain only in XMP, avoiding serializer vs Info string mismatches.
     */
    private void stripDocumentInfoPairedWithXmp(PDDocument doc) {
        org.apache.pdfbox.pdmodel.PDDocumentInformation info = doc.getDocumentInformation();
        if (info == null) {
            return;
        }
        COSDictionary cos = info.getCOSObject();
        cos.removeItem(COSName.TITLE);
        cos.removeItem(COSName.AUTHOR);
        cos.removeItem(COSName.SUBJECT);
        cos.removeItem(COSName.KEYWORDS);
        cos.removeItem(COSName.CREATOR);
        cos.removeItem(COSName.PRODUCER);
        cos.removeItem(COSName.CREATION_DATE);
        cos.removeItem(COSName.MOD_DATE);
    }

    private String sanitizeXmlString(String input) {
        if (input == null) {
            return null;
        }
        return input.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
    }

    private void sanitiseInteractiveElements(PDDocument doc) {
        PDDocumentCatalog catalog = doc.getDocumentCatalog();
        if (catalog.getCOSObject().containsKey(COSName.AA)) {
            catalog.getCOSObject().removeItem(COSName.AA);
            log.debug("Removed AA dictionary from Document Catalog");
        }
        if (catalog.getCOSObject().containsKey(COSName.OCPROPERTIES)) {
            catalog.getCOSObject().removeItem(COSName.OCPROPERTIES);
            log.debug("Removed OCProperties from Document Catalog");
        }
        for (PDPage page : doc.getPages()) {
            if (page.getCOSObject().containsKey(COSName.AA)) {
                page.getCOSObject().removeItem(COSName.AA);
            }
        }
    }
}
