package edu.usc.csci201.team3;

import edu.usc.csci201.team3.ReceiptParser.Line;
import edu.usc.csci201.team3.ReceiptParser.Page;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReceiptParserTest {
    private static final String CAFE = """
            BELLA'S CAFE
            123 Main Street
            Los Angeles CA 90007
            Oct 10, 2026
            Coffee            4.50
            2 Avocado Toast  12.00
            Latte             5.00
            Subtotal         21.50
            Tax               2.04
            Tip               3.00
            Total            26.54
            VISA ****1234    26.54
            Thank you!
            """;

    private static Page page(String text) {
        List<Line> lines = List.of(
                new Line("BELLA'S CAFE", 10, 5, 200, 40, 92),
                new Line("123 Main Street", 10, 50, 180, 20, 90),
                new Line("Los Angeles CA 90007", 10, 75, 200, 20, 90),
                new Line("Coffee 4.50", 10, 200, 200, 20, 90));
        return new Page(text, lines, 600);
    }

    @Test void extractsFieldsItemsAndExtras() {
        var draft = ReceiptParser.combine(List.of(page(CAFE)));
        assertEquals("BELLA'S CAFE", draft.merchant());
        assertEquals(2654L, draft.totalCents());
        assertEquals(2150L, draft.subtotalCents());
        assertEquals(204L, draft.taxCents());
        assertEquals(300L, draft.tipCents());
        assertEquals("2026-10-10", draft.date());
        assertEquals("Dining", draft.category());
        assertEquals(3, draft.items().size());
        assertEquals(new ReceiptParser.DraftItem("Avocado Toast", 2, 1200), draft.items().get(1));
    }

    @Test void subtotalIsNotMistakenForTotal() {
        assertEquals(1099L, ReceiptParser.extractTotal("Subtotal 9.99\nTotal: $10.99"));
    }

    @Test void passesVoteOnTheTotal() {
        var draft = ReceiptParser.combine(List.of(
                page("Total 26.54"), page("Total 26.54"), page("Total 28.54")));
        assertEquals(2654L, draft.totalCents());
    }

    @Test void europeanDecimalCommasAreUnderstood() {
        assertEquals(123456L, ReceiptParser.toCents("1.234,56"));
        assertEquals(123456L, ReceiptParser.toCents("1,234.56"));
    }

    @Test void numericDatesParse() {
        assertEquals("2026-11-03", ReceiptParser.extractDate("Date: 11/03/2026 18:22"));
        assertNull(ReceiptParser.extractDate("no date here"));
    }

    @Test void unreadableTextGivesEmptyDraft() {
        var draft = ReceiptParser.combine(List.of(new Page("~~ ## ~~", List.of(), 100)));
        assertNull(draft.totalCents());
        assertTrue(draft.items().isEmpty());
        assertNull(draft.merchant());
    }
}
