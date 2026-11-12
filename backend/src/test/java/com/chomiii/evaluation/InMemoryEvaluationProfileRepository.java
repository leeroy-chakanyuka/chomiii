package com.chomiii.evaluation;

import com.chomiii.memory.MemoryProfileRepository;
import com.chomiii.memory.ProfileParagraph;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Offline stand-in for the derived profile rows. It keeps the same revision
 * stamps and paragraph structure in memory so {@link com.chomiii.memory.MemoryService}
 * profile composition and staleness checks run without a database. Database
 * modes exercise the real repository through the disposable store instead; this
 * class is only for evaluation tests that must stay offline.
 */
final class InMemoryEvaluationProfileRepository extends MemoryProfileRepository {
    private record Row(String category, int position, String text, long ownerMemoryRevision,
            long contentRevision, List<UUID> sourceIds) {}
    private record OwnerState(long contentRevision, long builtContentRevision,
            long basedOnRevision, String snapshotText) {}
    private final Map<String, OwnerState> states = new HashMap<>();
    private final Map<String, List<Row>> paragraphs = new HashMap<>();

    InMemoryEvaluationProfileRepository() { super(null); }

    @Override public synchronized ProfileState state(String owner) {
        var state = states.get(owner);
        return state == null ? null : new ProfileState(state.contentRevision(),
                state.builtContentRevision(), state.basedOnRevision(), state.snapshotText());
    }

    @Override public synchronized long contentRevision(String owner) {
        var state = states.get(owner);
        return state == null ? 0L : state.contentRevision();
    }

    @Override public synchronized List<ProfileParagraphRow> paragraphs(String owner) {
        return paragraphs.getOrDefault(owner, List.of()).stream()
                .map(row -> new ProfileParagraphRow(row.category(), row.position(), row.text(),
                        row.ownerMemoryRevision(), row.contentRevision(),
                        row.sourceIds().size(), row.sourceIds()))
                .toList();
    }

    @Override public synchronized void ensureRow(String owner) {
        states.putIfAbsent(owner, new OwnerState(0, -1, -1, null));
    }

    @Override public synchronized void bumpContentRevision(String owner) {
        var state = states.getOrDefault(owner, new OwnerState(0, -1, -1, null));
        states.put(owner, new OwnerState(state.contentRevision() + 1, state.builtContentRevision(),
                state.basedOnRevision(), state.snapshotText()));
    }

    @Override public synchronized void replaceParagraphs(String owner, String snapshotText,
            long ownerMemoryRevision, long contentRevision, List<ProfileParagraph> paragraphs) {
        var stored = new ArrayList<Row>();
        for (int position = 0; position < paragraphs.size(); position++) {
            var paragraph = paragraphs.get(position);
            stored.add(new Row(paragraph.category(), position, paragraph.text(),
                    ownerMemoryRevision, contentRevision, paragraph.sourceIds()));
        }
        this.paragraphs.put(owner, stored);
        states.put(owner, new OwnerState(contentRevision, contentRevision, ownerMemoryRevision,
                snapshotText));
    }
}
