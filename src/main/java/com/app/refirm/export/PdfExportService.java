package com.app.refirm.export;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;

import java.io.ByteArrayOutputStream;

@Service
public class PdfExportService {

    private final Parser flexmarkParser;
    private final HtmlRenderer flexmarkRenderer;

    public PdfExportService() {
        MutableDataSet options = new MutableDataSet();
        this.flexmarkParser = Parser.builder(options).build();
        this.flexmarkRenderer = HtmlRenderer.builder(options).build();
    }

    public byte[] generateLegalPdf(String rawMarkdown) {
        if (rawMarkdown == null || rawMarkdown.isBlank()) {
            throw new IllegalArgumentException("Markdown brief cannot be empty.");
        }

        // 1. Sanitize code fences and replace Unicode Rupee symbol with 'Rs. ' to prevent PDFBox font glyph drops
        String sanitizedMarkdown = rawMarkdown
                .replaceAll("(?s)^```(?:markdown)?\\s*", "")
                .replaceAll("(?s)```\\s*$", "")
                .replace("₹", "Rs. ");

        // 2. Convert Markdown to HTML
        com.vladsch.flexmark.util.ast.Node document = flexmarkParser.parse(sanitizedMarkdown);
        String rawHtmlBody = flexmarkRenderer.render(document);

        // 3. Wrap in court-formatted HTML with @page pagination rules
        String fullHtml = buildHtmlDocument(rawHtmlBody);

        // 4. Sanitize and convert HTML to W3C DOM using JSoup (Prevents SAXParseException on unescaped &)
        org.jsoup.nodes.Document jsoupDoc = Jsoup.parse(fullHtml);
        jsoupDoc.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml);
        Document w3cDoc = new W3CDom().fromJsoup(jsoupDoc);

        // 5. Render to PDF byte array
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withW3cDocument(w3cDoc, null);
            builder.toStream(outputStream);
            builder.run();
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to render legal brief to PDF: " + e.getMessage(), e);
        }
    }

    public byte[] generatePdfFromMarkdown(String rawMarkdown) {
        return generateLegalPdf(rawMarkdown);
    }

    private String buildHtmlDocument(String bodyHtml) {
        return """
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head>
                <meta charset="UTF-8"/>
                <style>
                    @page {
                        size: A4;
                        margin: 25mm 20mm 25mm 20mm;
                        @bottom-right {
                            content: "Page " counter(page) " of " counter(pages);
                            font-family: 'Times New Roman', serif;
                            font-size: 9pt;
                            color: #555555;
                        }
                        @bottom-left {
                            content: "CONFIDENTIAL & PRIVILEGED LEGAL ASSESSMENT";
                            font-family: 'Times New Roman', serif;
                            font-size: 8pt;
                            color: #777777;
                        }
                    }
                    body {
                        font-family: 'Times New Roman', Times, serif;
                        font-size: 11pt;
                        line-height: 1.5;
                        color: #111111;
                    }
                    h2 {
                        font-size: 13pt;
                        font-weight: bold;
                        text-transform: uppercase;
                        margin-top: 18pt;
                        margin-bottom: 8pt;
                        border-bottom: 1.5px solid #222222;
                        padding-bottom: 2pt;
                        page-break-after: avoid;
                    }
                    p, li {
                        text-align: justify;
                        margin-bottom: 6pt;
                    }
                    ul, ol {
                        margin-top: 4pt;
                        margin-bottom: 8pt;
                        padding-left: 20pt;
                    }
                    li {
                        margin-bottom: 4pt;
                    }
                    blockquote {
                        border-left: 3px solid #2563EB;
                        padding-left: 10pt;
                        margin-left: 0;
                        font-style: italic;
                        color: #1F2937;
                    }
                </style>
            </head>
            <body>
                <div style="text-align: center; margin-bottom: 20pt;">
                    <div style="font-size: 15pt; font-weight: bold; letter-spacing: 1px;">REFIRM LEGAL STRATEGY BRIEF</div>
                    <div style="font-size: 9pt; color: #555555; margin-top: 3pt;">PREPARED UNDER STATUTORY DISCLOSURE PROTOCOLS</div>
                </div>
            """ + bodyHtml + """
            </body>
            </html>
            """;
    }
}
