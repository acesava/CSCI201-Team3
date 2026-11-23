package edu.usc.csci201.team3;

import edu.usc.csci201.team3.Models.Amount;
import edu.usc.csci201.team3.Models.Expense;
import edu.usc.csci201.team3.Models.Item;
import edu.usc.csci201.team3.Models.Participant;
import edu.usc.csci201.team3.Models.Settlement;
import edu.usc.csci201.team3.Models.User;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JSON shapes returned to the frontend. Money fields end in "Cents". */
final class Views {
    private Views() {}

    static Map<String, Object> user(User user) {
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("expenseNotifications", user.preferences().expenseNotifications());
        prefs.put("monthlySummaryEmail", user.preferences().monthlySummaryEmail());
        prefs.put("currency", user.preferences().currency());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.id());
        view.put("email", user.email());
        view.put("displayName", user.displayName());
        view.put("preferences", prefs);
        view.put("createdAt", user.createdAt().toString());
        return view;
    }

    static Map<String, Object> participant(Participant p) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", p.id());
        view.put("displayName", p.displayName());
        view.put("phone", p.phone());
        view.put("isYou", p.isSelf());
        view.put("archived", p.archived());
        return view;
    }

    static Map<String, Object> expense(Expense e, Map<UUID, String> names, boolean includeItems) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", e.id());
        view.put("description", e.description());
        view.put("category", e.category());
        view.put("date", e.date().toString());
        view.put("amountCents", e.amountCents());
        view.put("taxCents", e.taxCents());
        view.put("tipCents", e.tipCents());
        view.put("entryMethod", e.entryMethod());
        view.put("splitMethod", e.splitMethod());
        view.put("notes", e.notes());
        view.put("createdAt", e.createdAt().toString());
        view.put("payments", amounts(e.payments(), names));
        view.put("shares", amounts(e.shares(), names));
        if (includeItems) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Item item : e.items()) {
                Map<String, Object> itemView = new LinkedHashMap<>();
                itemView.put("id", item.id());
                itemView.put("name", item.name());
                itemView.put("quantity", item.quantity());
                itemView.put("totalCents", item.totalCents());
                itemView.put("allocations", amounts(item.allocations(), names));
                items.add(itemView);
            }
            view.put("items", items);
        }
        return view;
    }

    static List<Map<String, Object>> amounts(List<Amount> amounts, Map<UUID, String> names) {
        return amounts.stream().map(a -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("participantId", a.participantId());
            view.put("name", names.getOrDefault(a.participantId(), "Unknown"));
            view.put("amountCents", a.amountCents());
            return view;
        }).toList();
    }

    static Map<String, Object> settlement(Settlement s, Map<UUID, String> names) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", s.id());
        view.put("fromParticipantId", s.fromParticipantId());
        view.put("fromName", names.getOrDefault(s.fromParticipantId(), "Unknown"));
        view.put("toParticipantId", s.toParticipantId());
        view.put("toName", names.getOrDefault(s.toParticipantId(), "Unknown"));
        view.put("amountCents", s.amountCents());
        view.put("date", s.date().toString());
        view.put("method", s.method());
        view.put("note", s.note());
        view.put("status", s.status());
        view.put("createdAt", s.createdAt().toString());
        view.put("confirmedAt", s.confirmedAt() == null ? null : s.confirmedAt().toString());
        return view;
    }
}
