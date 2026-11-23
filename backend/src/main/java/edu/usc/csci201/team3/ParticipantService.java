package edu.usc.csci201.team3;

import edu.usc.csci201.team3.Models.Participant;
import edu.usc.csci201.team3.Models.User;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** The account holder's list of people to split with. Participants never need an account. */
final class ParticipantService {
    static final int MAX_PARTICIPANTS = 200;
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private final Repository repo;
    private final Clock clock;

    ParticipantService(Repository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    List<Participant> all(User user) {
        return repo.listParticipants(user.id());
    }

    Participant self(User user) {
        return all(user).stream().filter(Participant::isSelf).findFirst()
                .orElseThrow(() -> new IllegalStateException("Account has no self participant"));
    }

    Participant require(User user, UUID participantId) {
        return all(user).stream().filter(p -> p.id().equals(participantId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Participant"));
    }

    List<Map<String, Object>> list(User user, boolean includeArchived) {
        return all(user).stream().filter(p -> includeArchived || !p.archived()).map(Views::participant).toList();
    }

    Map<String, Object> create(User user, Body body) {
        List<Participant> existing = all(user);
        if (existing.size() >= MAX_PARTICIPANTS) throw ApiException.badRequest("You have reached the participant limit");
        Participant participant = new Participant(UUID.randomUUID(), user.id(), body.text("displayName", 60),
                phone(body), false, false, clock.instant());
        repo.saveParticipant(participant);
        return Views.participant(participant);
    }

    Map<String, Object> update(User user, UUID participantId, Body body) {
        Participant current = require(user, participantId);
        if (current.isSelf() && body.has("displayName")) {
            throw ApiException.badRequest("Change your own name from your profile");
        }
        String name = body.has("displayName") ? body.text("displayName", 60) : current.displayName();
        // "phone": null or "" clears the number; omitting the field keeps it.
        String phone = body.present("phone") ? phone(body) : current.phone();
        Boolean archived = body.optionalBoolean("archived");
        if (current.isSelf() && Boolean.TRUE.equals(archived)) throw ApiException.badRequest("You cannot archive yourself");
        Participant updated = new Participant(current.id(), current.ownerId(), name, phone, current.isSelf(),
                archived == null ? current.archived() : archived, current.createdAt());
        repo.saveParticipant(updated);
        return Views.participant(updated);
    }

    /** People with history are archived instead of deleted so past balances stay correct. */
    Map<String, Object> remove(User user, UUID participantId) {
        Participant current = require(user, participantId);
        if (current.isSelf()) throw ApiException.badRequest("You cannot remove yourself");
        if (repo.participantHasRecords(participantId)) {
            repo.saveParticipant(new Participant(current.id(), current.ownerId(), current.displayName(), current.phone(),
                    false, true, current.createdAt()));
            return Map.of("archived", true);
        }
        repo.deleteParticipant(participantId);
        return Map.of("deleted", true);
    }

    /** Accepts E.164 (+12135550100) or a 10-digit US number, which is normalized to E.164. */
    static String phone(Body body) {
        String raw = body.optionalText("phone", 25);
        if (raw == null) return null;
        String digits = raw.replaceAll("[\\s().-]", "");
        if (digits.matches("\\d{10}")) digits = "+1" + digits;
        if (digits.matches("1\\d{10}")) digits = "+" + digits;
        if (!E164.matcher(digits).matches()) {
            throw ApiException.badRequest("Enter the phone number with its country code, for example +12135550100");
        }
        return digits;
    }
}
