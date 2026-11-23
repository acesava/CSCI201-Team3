package edu.usc.csci201.team3;

import edu.usc.csci201.team3.Models.User;
import edu.usc.csci201.team3.ReceiptParser.Draft;
import edu.usc.csci201.team3.ReceiptParser.Page;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;

/**
 * Receipt scanning. Several OCR passes run on worker threads at once and the parser votes across them.
 * The result is only a draft: the user reviews and corrects it before saving an expense.
 */
final class ReceiptService {
    /** One OCR pass. Implementations must not share engine state between threads. */
    interface OcrEngine {
        Page recognize(BufferedImage image, int pageSegMode) throws Exception;
    }

    private record Pass(BufferedImage image, int pageSegMode) {}

    static final int DAILY_SCAN_LIMIT = 20;
    static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    static final long MAX_PIXELS = 20_000_000L;
    private static final int WORKERS = 3;
    private static final long DEADLINE_MILLIS = 20_000;
    private final OcrEngine engine;
    private final Repository repo;
    private final Clock clock;

    ReceiptService(OcrEngine engine, Repository repo, Clock clock) {
        this.engine = engine;
        this.repo = repo;
        this.clock = clock;
    }

    Map<String, Object> scan(User user, Body body) {
        BufferedImage image = decode(body.text("imageBase64", MAX_IMAGE_BYTES * 2));
        if (!repo.consumeQuota(user.id(), "ocr", LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC), DAILY_SCAN_LIMIT)) {
            throw new ApiException(429, "You have reached today's receipt scan limit. You can still enter the receipt manually.");
        }
        List<Page> pages = runPasses(image);
        if (pages.isEmpty()) {
            throw new ApiException(422, "We could not read this receipt. Try a clearer photo or enter the details manually.");
        }
        Draft draft = ReceiptParser.combine(pages);
        if (draft.totalCents() == null && draft.items().isEmpty()) {
            throw new ApiException(422, "We could not find any prices on this receipt. Try a clearer photo or enter the details manually.");
        }
        return view(draft);
    }

    private List<Page> runPasses(BufferedImage original) {
        BufferedImage enlarged = enlarge(original);
        BufferedImage header = original.getSubimage(0, 0, original.getWidth(), Math.max(1, original.getHeight() / 4));
        List<Pass> passes = List.of(new Pass(original, 3), new Pass(original, 4), new Pass(enlarged, 4),
                new Pass(enlarged, 6), new Pass(header, 6));
        List<Page> pages = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS);
        boolean engineMissing = false;
        // Every task finishes or is cancelled before this block exits, so no work outlives the invocation.
        try (ExecutorService workers = Executors.newFixedThreadPool(WORKERS)) {
            List<Future<Page>> futures = new ArrayList<>();
            for (Pass pass : passes) futures.add(workers.submit(() -> engine.recognize(pass.image(), pass.pageSegMode())));
            for (Future<Page> future : futures) {
                try {
                    pages.add(future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (TimeoutException slow) {
                    future.cancel(true);
                } catch (ExecutionException failed) {
                    if (failed.getCause() instanceof LinkageError) engineMissing = true;
                    System.err.println("OCR pass failed: " + failed.getCause().getClass().getSimpleName());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            workers.shutdownNow();
        }
        if (pages.isEmpty() && engineMissing) {
            throw new ApiException(503, "Receipt scanning is temporarily unavailable. Please enter the receipt manually.");
        }
        return pages;
    }

    static BufferedImage decode(String base64) {
        String data = base64;
        int comma = data.indexOf(',');
        if (data.startsWith("data:") && comma > 0) data = data.substring(comma + 1);
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(data);
        } catch (IllegalArgumentException invalid) {
            throw ApiException.badRequest("The image could not be decoded");
        }
        if (bytes.length == 0) throw ApiException.badRequest("The image is empty");
        if (bytes.length > MAX_IMAGE_BYTES) throw new ApiException(413, "The image must be 4 MB or smaller");
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) throw ApiException.badRequest("Upload a JPEG or PNG image");
            if ((long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
                throw new ApiException(413, "The image resolution is too high");
            }
            return image;
        } catch (IOException unreadable) {
            throw ApiException.badRequest("Upload a JPEG or PNG image");
        }
    }

    /** Small photos are scaled up in grayscale because Tesseract reads larger glyphs more reliably. */
    static BufferedImage enlarge(BufferedImage image) {
        int scale = image.getWidth() < 1000 ? 3 : 1;
        BufferedImage result = new BufferedImage(image.getWidth() * scale, image.getHeight() * scale, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(image, 0, 0, result.getWidth(), result.getHeight(), null);
        g.dispose();
        return result;
    }

    private static Map<String, Object> view(Draft draft) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("merchant", draft.merchant());
        view.put("totalCents", draft.totalCents());
        view.put("subtotalCents", draft.subtotalCents());
        view.put("taxCents", draft.taxCents());
        view.put("tipCents", draft.tipCents());
        view.put("date", draft.date());
        view.put("category", draft.category());
        view.put("items", draft.items().stream().map(item -> {
            Map<String, Object> itemView = new LinkedHashMap<>();
            itemView.put("name", item.name());
            itemView.put("quantity", item.quantity());
            itemView.put("totalCents", item.totalCents());
            return itemView;
        }).toList());
        view.put("needsReview", true);
        return view;
    }
}
