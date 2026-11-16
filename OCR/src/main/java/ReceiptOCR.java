
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;

public class ReceiptOCR {
    static class TextLine {
        String text;
        Rectangle bounds;
        double confidence;

        TextLine(String text, Rectangle bounds, double confidence) {
            this.text = text;
            this.bounds = bounds;
            this.confidence = confidence;
        }
    }

    static class Candidate {
        String text;
        double score;

        Candidate(String text, double score) {
            this.text = text;
            this.score = score;
        }
    }

    static class ScanResult {
        String text;
        List<TextLine> lines;
        Candidate merchant;
        BigDecimal total;
        String date;

        ScanResult(String text, List<TextLine> lines, int imageHeight) {
            this.text = text;
            this.lines = lines;
            this.merchant = findMerchant(lines, imageHeight);
            this.total = extractTotal(text);
            this.date = extractDate(text);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: ReceiptOCR <image-file>");
            return;
        }

        File file = new File(args[0]);

        if (!file.isFile()) {
            System.out.println("Image not found: " + file);
            return;
        }

        List<ScanResult> scans = scanReceipt(file);

        String merchant = chooseMerchant(scans);
        BigDecimal total = chooseTotal(scans);
        String date = chooseDate(scans);
        String category = chooseCategory(scans, merchant);

        String expenseId = UUID.randomUUID().toString();
        String receiptId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        String json = """
            {
              "expense": {
                "expense_id": %s,
                "user_id": null,
                "expense_name": %s,
                "total_amount": %s,
                "expense_date": %s,
                "expense_category": %s,
                "payer_id": null,
                "created_at": %s,
                "updated_at": %s
              },
              "receipt": {
                "receipt_id": %s,
                "expense_id": %s,
                "receipt_image": %s
              }
            }
            """.formatted(
                jsonString(expenseId),
                jsonString(merchant),
                total == null ? "null" : total.toPlainString(),
                jsonString(date),
                jsonString(category),
                jsonString(now),
                jsonString(now),
                jsonString(receiptId),
                jsonString(expenseId),
                jsonString(args[0])
            );

        Files.writeString(Path.of("expense.json"), json);

        System.out.println("\n=== PARSED EXPENSE ===");
        System.out.println(json);
        System.out.println("Saved to expense.json");
    }

    static List<ScanResult> scanReceipt(File file) throws Exception {
        BufferedImage original = ImageIO.read(file);

        if (original == null) {
            throw new IllegalArgumentException("Unsupported image");
        }

        BufferedImage enlarged = enlarge(original);
        List<ScanResult> scans = new ArrayList<>();

        scans.add(runOCR(original, 3, "Original PSM 3"));
        scans.add(runOCR(original, 4, "Original PSM 4"));
        scans.add(runOCR(enlarged, 4, "Enlarged PSM 4"));
        scans.add(runOCR(enlarged, 6, "Enlarged PSM 6"));
        int headerHeight = Math.max(1, original.getHeight() / 4);

        BufferedImage header = original.getSubimage(
            0,
            0,
            original.getWidth(),
            headerHeight
        );
        scans.add(runOCR(header, 6, "Header PSM 6"));
        return scans;
    }

    static BufferedImage enlarge(BufferedImage image) {
        int scale = image.getWidth() < 1000 ? 3 : 1;

        BufferedImage result = new BufferedImage(
            image.getWidth() * scale,
            image.getHeight() * scale,
            BufferedImage.TYPE_BYTE_GRAY
        );

        Graphics2D g = result.createGraphics();

        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC
        );

        g.drawImage(
            image, 0, 0,
            result.getWidth(),
            result.getHeight(),
            null
        );

        g.dispose();
        return result;
    }

    static ScanResult runOCR(
            BufferedImage image,
            int mode,
            String label) throws Exception {

        Tesseract ocr = new Tesseract();
        ocr.setDatapath("tessdata");
        ocr.setLanguage("eng");
        ocr.setPageSegMode(mode);

        String text = ocr.doOCR(image);

        List<Word> words = ocr.getWords(
            image,
            ITessAPI.TessPageIteratorLevel.RIL_WORD
        );

        List<TextLine> lines = groupLines(words);

        ScanResult result = new ScanResult(
            text, lines, image.getHeight()
        );

        System.out.println("\n=== " + label + " ===");
        System.out.println(text);

        System.out.println("Merchant candidate: " +
            (result.merchant == null
                ? "none"
                : result.merchant.text + " (score " +
                  String.format(
                      Locale.US, "%.2f",
                      result.merchant.score
                  ) + ")")
        );

        return result;
    }

    static List<TextLine> groupLines(List<Word> words) {
        List<Word> valid = new ArrayList<>();

        for (Word word : words) {
            if (word.getText() != null
                    && !word.getText().isBlank()
                    && word.getBoundingBox() != null) {
                valid.add(word);
            }
        }

        valid.sort(
            Comparator.comparingInt(
                (Word w) -> w.getBoundingBox().y
            ).thenComparingInt(
                w -> w.getBoundingBox().x
            )
        );

        List<List<Word>> groups = new ArrayList<>();

        for (Word word : valid) {
            Rectangle box = word.getBoundingBox();
            List<Word> match = null;

            for (List<Word> group : groups) {
                Rectangle first = group.get(0).getBoundingBox();

                int centerA = first.y + first.height / 2;
                int centerB = box.y + box.height / 2;

                int tolerance = Math.max(
                    4,
                    Math.min(first.height, box.height) / 2
                );

                if (Math.abs(centerA - centerB) <= tolerance) {
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

        List<TextLine> lines = new ArrayList<>();

        for (List<Word> group : groups) {
            group.sort(
                Comparator.comparingInt(
                    w -> w.getBoundingBox().x
                )
            );

            StringBuilder text = new StringBuilder();
            Rectangle bounds = null;
            double weightedConfidence = 0;
            int chars = 0;

            for (Word word : group) {
                String part = word.getText().trim();

                if (part.isEmpty()) {
                    continue;
                }

                if (text.length() > 0) {
                    text.append(' ');
                }

                text.append(part);

                bounds = bounds == null
                    ? new Rectangle(word.getBoundingBox())
                    : bounds.union(word.getBoundingBox());

                weightedConfidence +=
                    word.getConfidence() * part.length();

                chars += part.length();
            }

            if (bounds != null && chars > 0) {
                lines.add(new TextLine(
                    text.toString(),
                    bounds,
                    weightedConfidence / chars
                ));
            }
        }

        lines.sort(
            Comparator.comparingInt(l -> l.bounds.y)
        );

        return lines;
    }

    static Candidate findMerchant(
            List<TextLine> lines,
            int imageHeight) {

        if (lines.isEmpty()) {
            return null;
        }

        List<Integer> heights = new ArrayList<>();

        for (TextLine line : lines) {
            heights.add(line.bounds.height);
        }

        heights.sort(Integer::compareTo);

        double medianHeight =
            heights.get(heights.size() / 2);

        Candidate best = null;

        for (int i = 0; i < lines.size(); i++) {
            TextLine line = lines.get(i);

            if (line.bounds.y > imageHeight * 0.38) {
                continue;
            }

            String value = cleanLine(line.text);

            if (!isMerchantCandidate(value)) {
                continue;
            }

            double topFraction =
                (double) line.bounds.y / imageHeight;

            double position = Math.max(
                0, 1 - topFraction / 0.38
            );

            double size = Math.min(
                1,
                line.bounds.height /
                Math.max(1, medianHeight * 1.8)
            );

            double confidence = Math.max(
                0,
                Math.min(1, line.confidence / 100)
            );

            double context = 0;

            for (int j = i + 1;
                    j < Math.min(lines.size(), i + 4);
                    j++) {

                if (isAddress(lines.get(j).text)) {
                    context = 1;
                    break;
                }
            }

            double score =
                  0.35 * position
                + 0.20 * size
                + 0.20 * confidence
                + 0.25 * context;

            if (best == null || score > best.score) {
                best = new Candidate(value, score);
            }
        }

        return best;
    }

    static String cleanLine(String text) {
        return text.trim()
            .replaceAll("^[^\\p{L}\\d]+", "")
            .replaceAll("[^\\p{L}\\d)]+$", "")
            .replaceAll("\\s+", " ");
    }

    static boolean isAddress(String text) {
        String s = text.toLowerCase(Locale.ROOT).trim();

        if (s.matches(
                ".*\\b\\S+\\s+" +
                "(road|rd|street|st|avenue|ave|" +
                "drive|dr|lane|ln|blvd|boulevard|" +
                "court|ct|highway|hwy)\\b.*")) {
            return true;
        }

        if (s.matches(
                ".*\\b[a-z]{2}\\s+\\d{5}" +
                "(?:-\\d{4})?\\b.*")) {
            return true;
        }

        if (s.matches(
                ".*\\b\\d{5}(?:-\\d{4})?\\b.*")) {
            return true;
        }

        return false;
    }

    static boolean isMerchantCandidate(String text) {
        if (text.length() < 3 || isAddress(text)) {
            return false;
        }

        String lower = text.toLowerCase(Locale.ROOT);

        if (lower.matches(
                ".*\\b(server|check|table|ordered|" +
                "subtotal|total|receipt|invoice|tax|" +
                "tip|change|cash|visa|mastercard|" +
                "phone|date|time|dining|customer|" +
                "thank|please|balance|amount)\\b.*")) {
            return false;
        }

        if (lower.contains("www.")
                || lower.contains("http")
                || lower.contains("@")) {
            return false;
        }

        if (text.matches(
                ".*\\(?\\d{3}\\)?[- .]?\\d{3}" +
                "[- .]?\\d{4}.*")) {
            return false;
        }

        if (text.matches("^\\d+.*")) {
            return false;
        }

        long letters = text.chars()
            .filter(Character::isLetter)
            .count();

        long digits = text.chars()
            .filter(Character::isDigit)
            .count();

        return letters >= 3
            && digits <= 2
            && letters >= text.length() * 0.5;
    }

    static String chooseMerchant(List<ScanResult> scans) {
        Map<String, Double> bestScores = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> names = new HashMap<>();

        for (ScanResult scan : scans) {
            if (scan.merchant == null) {
                continue;
            }

            String key = scan.merchant.text
                .toLowerCase(Locale.ROOT);

            bestScores.merge(
                key, scan.merchant.score, Math::max
            );

            counts.merge(key, 1, Integer::sum);
            names.putIfAbsent(key, scan.merchant.text);
        }

        String best = null;
        double highest = 0;

        for (String key : bestScores.keySet()) {
            double score = bestScores.get(key)
                + 0.04 * Math.min(
                    3, counts.get(key) - 1
                );

            if (score > highest) {
                highest = score;
                best = names.get(key);
            }
        }

        // Leave uncertain merchant names blank
        return highest >= 0.55 ? best : null;
    }

    static BigDecimal extractTotal(String text) {
        String[] lines = text.split("\\R");

        Pattern labeled = Pattern.compile(
            "(?i)^\\s*(?:grand\\s+total|" +
            "total\\s+due|amount\\s+due|total)" +
            "\\s*[:$€£\\s]*([\\d.,\\s]+)"
        );

        Pattern money = Pattern.compile(
            "(?<!\\d)(\\d+(?:[.,]\\d{3})*[.,]\\d{2}" +
            "|\\d+\\s*[.,]\\s*\\d{2})(?!\\d)"
        );

        for (String line : lines) {
            Matcher m = labeled.matcher(line);

            if (m.find()) {
                BigDecimal amount = parseAmount(m.group(1));

                if (amount != null) {
                    return amount;
                }
            }
        }

        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].toLowerCase(Locale.ROOT)
                    .contains("subtotal")) {
                continue;
            }

            BigDecimal last = null;

            for (int j = i + 1;
                    j < Math.min(lines.length, i + 6);
                    j++) {

                Matcher m = money.matcher(lines[j]);

                while (m.find()) {
                    BigDecimal amount =
                        parseAmount(m.group(1));

                    if (amount != null) {
                        last = amount;
                    }
                }
            }

            return last;
        }

        return null;
    }

    static BigDecimal parseAmount(String value) {
        try {
            String s = value.replaceAll("\\s+", "");

            int comma = s.lastIndexOf(',');
            int dot = s.lastIndexOf('.');

            if (comma > dot) {
                s = s.replace(".", "")
                     .replace(',', '.');
            } else {
                s = s.replace(",", "");
            }

            return new BigDecimal(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static BigDecimal chooseTotal(List<ScanResult> scans) {
        Map<BigDecimal, Integer> counts = new HashMap<>();

        for (ScanResult scan : scans) {
            if (scan.total != null) {
                counts.merge(scan.total, 1, Integer::sum);
            }
        }

        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    static String extractDate(String text) {
        Pattern monthDate = Pattern.compile(
            "(?i)\\b(Jan|Feb|Mar|Apr|May|Jun|" +
            "Jul|Aug|Sep|Oct|Nov|Dec)\\s*" +
            "(\\d{1,2})\\s*['°/.-]\\s*" +
            "(\\d{2,4})\\b"
        );

        Matcher m = monthDate.matcher(text);

        while (m.find()) {
            String[] months = {
                "jan", "feb", "mar", "apr",
                "may", "jun", "jul", "aug",
                "sep", "oct", "nov", "dec"
            };

            int month = 0;

            for (int i = 0; i < months.length; i++) {
                if (months[i].equalsIgnoreCase(m.group(1))) {
                    month = i + 1;
                }
            }

            int year = Integer.parseInt(m.group(3));

            if (year < 100) {
                year += year >= 70 ? 1900 : 2000;
            }

            try {
                return LocalDate.of(
                    year,
                    month,
                    Integer.parseInt(m.group(2))
                ).toString();
            } catch (RuntimeException ignored) {
            }
        }

        Pattern numeric = Pattern.compile(
            "\\b(?:\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}" +
            "|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4})\\b"
        );

        DateTimeFormatter[] formats = {
            dateFormat("M/d/uuuu"),
            dateFormat("M/d/uu"),
            dateFormat("M-d-uuuu"),
            dateFormat("M-d-uu"),
            dateFormat("uuuu-MM-dd"),
            dateFormat("uuuu/M/d"),
            dateFormat("d.M.uuuu"),
            dateFormat("d.M.uu"),
            dateFormat("d/M/uuuu")
        };

        m = numeric.matcher(text);

        while (m.find()) {
            for (DateTimeFormatter format : formats) {
                try {
                    return LocalDate.parse(
                        m.group(), format
                    ).toString();
                } catch (DateTimeParseException ignored) {
                }
            }
        }

        return null;
    }

    static DateTimeFormatter dateFormat(String format) {
        return DateTimeFormatter.ofPattern(
            format, Locale.US
        ).withResolverStyle(ResolverStyle.STRICT);
    }

    static String chooseDate(List<ScanResult> scans) {
        Map<String, Integer> counts = new HashMap<>();

        for (ScanResult scan : scans) {
            if (scan.date != null) {
                counts.merge(scan.date, 1, Integer::sum);
            }
        }

        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    static String chooseCategory(
            List<ScanResult> scans,
            String merchant) {

        StringBuilder combined = new StringBuilder();

        for (ScanResult scan : scans) {
            combined.append(scan.text).append('\n');
        }

        String name = merchant == null
            ? ""
            : merchant.toLowerCase(Locale.ROOT);

        String content = combined.toString()
            .toLowerCase(Locale.ROOT);

        if (containsAny(name,
                "lidl", "aldi", "costco", "walmart",
                "whole foods", "trader joe", "grocery",
                "supermarket", "tesco", "carrefour")) {
            return "Groceries";
        }

        if (containsAny(name,
                "uber", "lyft", "parking",
                "chevron", "shell", "metro")) {
            return "Transportation";
        }

        if (containsAny(name,
                "cvs", "walgreens", "pharmacy")) {
            return "Health";
        }

        if (containsAny(content,
                "server:", "your server",
                "thank you for dining", "sandwich",
                "burger", "grilled cheese", "pizza",
                "fries", "restaurant")
            || containsAny(name,
                "starbucks", "chipotle", "cafe",
                "coffee", "bakery")) {
            return "Food & Drinks";
        }

        return "Other";
    }

    static boolean containsAny(
            String text,
            String... words) {

        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }

        return false;
    }

    static String jsonString(String value) {
        if (value == null) {
            return "null";
        }

        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\"";
    }
}
