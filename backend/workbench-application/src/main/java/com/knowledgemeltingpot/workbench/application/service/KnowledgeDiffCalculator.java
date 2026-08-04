package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeDiffCalculator {
    public KnowledgeDiff compare(KnowledgeIr base, KnowledgeIr replacement) {
        Delta<KnowledgeIr.Rule> rules = delta(base.rules(), replacement.rules(), KnowledgeIr.Rule::id);
        Delta<KnowledgeIr.Flow> flows = delta(base.flows(), replacement.flows(), KnowledgeIr.Flow::id);
        return new KnowledgeDiff(rules.added(), rules.removed(), rules.changed(), flows.added(), flows.removed(),
                flows.changed(), replacement.sourceRefs().size() - base.sourceRefs().size());
    }

    private <T> Delta<T> delta(List<T> before, List<T> after, Function<T, String> id) {
        Map<String, T> left = before.stream().collect(Collectors.toMap(id, Function.identity()));
        Map<String, T> right = after.stream().collect(Collectors.toMap(id, Function.identity()));
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        right.forEach((key, value) -> {
            if (!left.containsKey(key)) added.add(key);
            else if (!left.get(key).equals(value)) changed.add(key);
        });
        left.keySet().stream().filter(key -> !right.containsKey(key)).forEach(removed::add);
        Comparator<String> order = Comparator.naturalOrder();
        added.sort(order);
        removed.sort(order);
        changed.sort(order);
        return new Delta<>(added, removed, changed);
    }

    private record Delta<T>(List<String> added, List<String> removed, List<String> changed) {
    }
}
