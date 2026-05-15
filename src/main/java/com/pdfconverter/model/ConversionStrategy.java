package com.pdfconverter.model;

/**
 * Strategy used to achieve PDF/A conversion.
 */
public enum ConversionStrategy {

    /**
     * Fixes the existing PDF structure in-place (preserves text selectability).
     */
    STRUCTURAL,

    /**
     * Renders pages to images and rebuilds as a new PDF/A (always valid, but loses text).
     */
    RENDER
}
