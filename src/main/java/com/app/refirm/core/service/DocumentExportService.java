package com.app.refirm.core.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Service
public class DocumentExportService {

    // --- PDF ENGINE (OpenHTMLToPDF) ---
    public byte[] generatePdfFromHtml(String rawHtml) {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {

            // 1. Sanitize TipTap HTML into strict XHTML
            Document document = Jsoup.parseBodyFragment(rawHtml);
            document.outputSettings().syntax(Document.OutputSettings.Syntax.xml);

            // 2. Inject standard Court formatting (A4, Margins, Times New Roman)
            String styledHtml = """
                <html>
                    <head>
                        <style>
                            @page { size: A4; margin: 1in; }
                            body { font-family: 'Times New Roman', serif; font-size: 12pt; line-height: 1.5; }
                            h1, h2, h3, h4 { text-align: center; font-weight: bold; }
                        </style>
                    </head>
                    <body>%s</body>
                </html>
                """.formatted(document.body().html());

            // 3. Render PDF
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(styledHtml, null);
            builder.toStream(os);
            builder.run();

            return os.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF document", e);
        }
    }

    // --- DOCX ENGINE (Apache POI) ---
    public byte[] generateDocxFromHtml(String rawHtml) {
        try (XWPFDocument docx = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Parse HTML DOM
            Document htmlDoc = Jsoup.parseBodyFragment(rawHtml);

            // Traverse the top-level elements (paragraphs, headers, lists)
            for (Element element : htmlDoc.body().children()) {
                XWPFParagraph paragraph = docx.createParagraph();

                // Align headers to center
                if (element.tagName().matches("h[1-6]")) {
                    paragraph.setAlignment(ParagraphAlignment.CENTER);
                }

                // Parse the inner text and formatting tags
                parseHtmlNodesToWord(element, paragraph, false, false);
            }

            docx.write(out);
            return out.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Failed to generate Word document", e);
        }
    }

    // Recursive HTML to Word DOM mapper
    private void parseHtmlNodesToWord(Node node, XWPFParagraph paragraph, boolean isBold, boolean isItalic) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode textNode) {
                String text = textNode.text();
                if (!text.trim().isEmpty() || text.equals(" ")) {
                    XWPFRun run = paragraph.createRun();
                    run.setFontFamily("Times New Roman");
                    run.setFontSize(12);
                    run.setBold(isBold);
                    run.setItalic(isItalic);
                    run.setText(text);
                }
            } else if (child instanceof Element element) {
                String tag = element.tagName();
                // If tag is strong/b, pass isBold as true down the tree
                boolean nextBold = isBold || tag.equals("strong") || tag.equals("b") || tag.matches("h[1-6]");
                boolean nextItalic = isItalic || tag.equals("em") || tag.equals("i");

                parseHtmlNodesToWord(child, paragraph, nextBold, nextItalic);

                // Add a simple line break for TipTap <br> tags
                if (tag.equals("br")) {
                    paragraph.createRun().addBreak();
                }
            }
        }
    }
}