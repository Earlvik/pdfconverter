package com.pdfconverter.model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Parameters for a PDF to PDF/A conversion request.
 */
public class ConversionRequest {

    private final byte[] inputBytes;
    private final PdfAFlavor flavor;
    private final ConversionStrategy preferredStrategy;

    private ConversionRequest(Builder builder) {
        this.inputBytes = builder.inputBytes;
        this.flavor = builder.flavor;
        this.preferredStrategy = builder.preferredStrategy;
    }

    public byte[] getInputBytes() {
        return inputBytes;
    }

    public PdfAFlavor getFlavor() {
        return flavor;
    }

    public ConversionStrategy getPreferredStrategy() {
        return preferredStrategy;
    }

    public static Builder builder(byte[] inputBytes) {
        return new Builder(inputBytes);
    }

    public static Builder builder(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return new Builder(buffer.toByteArray());
    }

    public static class Builder {
        private final byte[] inputBytes;
        private PdfAFlavor flavor = PdfAFlavor.PDFA_1B;
        private ConversionStrategy preferredStrategy = null;

        private Builder(byte[] inputBytes) {
            this.inputBytes = inputBytes;
        }

        public Builder flavor(PdfAFlavor flavor) {
            this.flavor = flavor;
            return this;
        }

        public Builder preferredStrategy(ConversionStrategy strategy) {
            this.preferredStrategy = strategy;
            return this;
        }

        public ConversionRequest build() {
            if (inputBytes == null || inputBytes.length == 0) throw new IllegalArgumentException("inputBytes is required and cannot be empty");
            if (flavor == null) throw new IllegalArgumentException("flavor is required");
            return new ConversionRequest(this);
        }
    }
}
