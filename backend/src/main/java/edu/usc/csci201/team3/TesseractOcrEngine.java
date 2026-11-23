package edu.usc.csci201.team3;

import edu.usc.csci201.team3.ReceiptParser.Line;
import edu.usc.csci201.team3.ReceiptParser.Page;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;

/**
 * Tess4J binding. Lambda needs libtesseract and leptonica on LD_LIBRARY_PATH (a Lambda layer)
 * and eng.traineddata under TESSDATA_PREFIX, which defaults to /opt/tessdata.
 */
final class TesseractOcrEngine implements ReceiptService.OcrEngine {
    private final String dataPath;

    TesseractOcrEngine(String dataPath) {
        this.dataPath = dataPath;
    }

    static TesseractOcrEngine fromEnvironment() {
        String path = System.getenv("TESSDATA_PREFIX");
        return new TesseractOcrEngine(path == null || path.isBlank() ? "/opt/tessdata" : path);
    }

    public Page recognize(BufferedImage image, int pageSegMode) throws Exception {
        // Tesseract instances are not thread-safe, so each pass gets its own.
        Tesseract ocr = new Tesseract();
        ocr.setDatapath(dataPath);
        ocr.setLanguage("eng");
        ocr.setPageSegMode(pageSegMode);
        String text = ocr.doOCR(image);
        List<Word> words = ocr.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD);
        return new Page(text, groupLines(words), image.getHeight());
    }

    /** Groups words whose vertical centers line up into text lines, top to bottom. */
    static List<Line> groupLines(List<Word> words) {
        List<Word> valid = new ArrayList<>();
        for (Word word : words) {
            if (word.getText() != null && !word.getText().isBlank() && word.getBoundingBox() != null) valid.add(word);
        }
        valid.sort(Comparator.comparingInt((Word w) -> w.getBoundingBox().y).thenComparingInt(w -> w.getBoundingBox().x));
        List<List<Word>> groups = new ArrayList<>();
        for (Word word : valid) {
            Rectangle box = word.getBoundingBox();
            List<Word> match = null;
            for (List<Word> group : groups) {
                Rectangle first = group.get(0).getBoundingBox();
                int tolerance = Math.max(4, Math.min(first.height, box.height) / 2);
                if (Math.abs((first.y + first.height / 2) - (box.y + box.height / 2)) <= tolerance) {
                    match = group;
                    break;
                }
            }
            if (match == null) {
                match = new ArrayList<>();
                groups.add(match);
            }
            match.add(word);
        }
        List<Line> lines = new ArrayList<>();
        for (List<Word> group : groups) {
            group.sort(Comparator.comparingInt(w -> w.getBoundingBox().x));
            StringBuilder text = new StringBuilder();
            Rectangle bounds = null;
            double weighted = 0;
            int chars = 0;
            for (Word word : group) {
                String part = word.getText().trim();
                if (text.length() > 0) text.append(' ');
                text.append(part);
                bounds = bounds == null ? new Rectangle(word.getBoundingBox()) : bounds.union(word.getBoundingBox());
                weighted += word.getConfidence() * part.length();
                chars += part.length();
            }
            if (bounds != null && chars > 0) {
                lines.add(new Line(text.toString(), bounds.x, bounds.y, bounds.width, bounds.height, weighted / chars));
            }
        }
        lines.sort(Comparator.comparingInt(Line::y));
        return lines;
    }
}
