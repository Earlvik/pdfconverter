package com.pdfconverter.service;

import com.pdfconverter.model.PdfAFlavor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.AdobePDFSchema;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.schema.XMPBasicSchema;
import org.apache.xmpbox.xml.XmpSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.List;

/**
 * Strategy 2: Render &amp; Rebuild converter.
 * <p>
 * Renders every page of the source PDF to a raster image at the configured DPI,
 * then assembles a new PDF/A-1b document from those images. This guarantees
 * compliance because the output contains only images + required metadata.
 * <p>
 * Trade-off: text is no longer selectable/searchable in the output.
 */
@Service
public class RenderConverter {

    private static final Logger log = LoggerFactory.getLogger(RenderConverter.class);
    /** Points per inch (PDF coordinates use 72 pt/in). */
    private static final float PT_PER_IN = 72f;

    /**
     * Renders the source PDF to images and builds a new PDF/A-1b document.
     *
     * @param sourceBytes bytes of the original (or preprocessed) PDF
     * @param flavor     target PDF/A flavor
     * @param dpi        rendering resolution in DPI (e.g. 300)
     * @param warnings   mutable warning list
     * @return rendered PDF/A byte array
     */
    public byte[] convert(byte[] sourceBytes, PdfAFlavor flavor, int dpi, List<String> warnings) throws IOException {
        try (PDDocument source = Loader.loadPDF(sourceBytes);
             PDDocument target = new PDDocument()) {

            PDFRenderer renderer = new PDFRenderer(source);
            int pageCount = source.getNumberOfPages();
            log.info("Rendering {} page(s) at {} DPI...", pageCount, dpi);

            for (int i = 0; i < pageCount; i++) {
                // Get original page dimensions (in points)
                PDPage sourcePage = source.getPage(i);
                PDRectangle mediaBox = sourcePage.getMediaBox();
                float pageWidthPt = mediaBox.getWidth();
                float pageHeightPt = mediaBox.getHeight();

                // Render to image
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);

                // Add new page matching original dimensions
                PDPage newPage = new PDPage(new PDRectangle(pageWidthPt, pageHeightPt));
                target.addPage(newPage);

                // Embed the image
                PDImageXObject pdImage = LosslessFactory.createFromImage(target, image);

                try (PDPageContentStream cs = new PDPageContentStream(
                        target, newPage, PDPageContentStream.AppendMode.OVERWRITE, false)) {
                    cs.drawImage(pdImage, 0, 0, pageWidthPt, pageHeightPt);
                }
                log.debug("Rendered page {}/{}", i + 1, pageCount);
            }

            // Add PDF/A metadata
            addPdfaXmpMetadata(target, flavor);
            addOutputIntent(target, warnings);

            // Set version
            target.getDocument().setVersion(1.4f);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            target.save(baos, CompressParameters.NO_COMPRESSION);
            log.info("Render conversion complete ({} pages) → {} bytes", pageCount, baos.size());
            return baos.toByteArray();
        }
    }

    private void addPdfaXmpMetadata(PDDocument doc, PdfAFlavor flavor) throws IOException {
        try {
            XMPMetadata xmp = XMPMetadata.createXMPMetadata();

            PDFAIdentificationSchema pdfaId = xmp.createAndAddPDFAIdentificationSchema();
            pdfaId.setPart(flavor.getPart());
            pdfaId.setConformance(flavor.getConformance().toUpperCase());

            DublinCoreSchema dc = xmp.createAndAddDublinCoreSchema();
            dc.setTitle("PDF/A Document");

            XMPBasicSchema basic = xmp.createAndAddXMPBasicSchema();
            basic.setCreateDate(Calendar.getInstance());
            basic.setModifyDate(Calendar.getInstance());
            basic.setCreatorTool("PDF Converter Library");

            AdobePDFSchema adobePdf = xmp.createAndAddAdobePDFSchema();
            adobePdf.setProducer("PDF Converter Library");

            XmpSerializer serializer = new XmpSerializer();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            serializer.serialize(xmp, baos, true);

            PDMetadata metadata = new PDMetadata(doc);
            metadata.importXMPMetadata(baos.toByteArray());
            doc.getDocumentCatalog().setMetadata(metadata);
        } catch (Exception e) {
            throw new IOException("Failed to add PDF/A XMP metadata", e);
        }
    }

    private void addOutputIntent(PDDocument doc, List<String> warnings) {
        try {
            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            catalog.getCOSObject().removeItem(COSName.getPDFName("OutputIntents"));

            byte[] iccBytes = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
            try (InputStream iccStream = new ByteArrayInputStream(iccBytes)) {
                PDOutputIntent oi = new PDOutputIntent(doc, iccStream);
                oi.setInfo("sRGB IEC61966-2.1");
                oi.setOutputCondition("sRGB IEC61966-2.1");
                oi.setOutputConditionIdentifier("sRGB IEC61966-2.1");
                oi.setRegistryName("http://www.color.org");
                oi.getCOSObject().setName(COSName.S, "GTS_PDFA1");
                
                org.apache.pdfbox.cos.COSBase profileStream = oi.getCOSObject().getDictionaryObject(COSName.DEST_OUTPUT_PROFILE);
                if (profileStream instanceof org.apache.pdfbox.cos.COSStream) {
                    ((org.apache.pdfbox.cos.COSStream) profileStream).setInt(COSName.N, 3);
                }
                
                catalog.addOutputIntent(oi);
            }
        } catch (Exception e) {
            log.warn("Failed to add Output Intent: {}", e.getMessage());
            warnings.add("Warning: could not add Output Intent: " + e.getMessage());
        }
    }
}
