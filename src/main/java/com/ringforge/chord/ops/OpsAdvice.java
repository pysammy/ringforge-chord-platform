package com.ringforge.chord.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OpsAdvice {
    private final List<String> summary;
    private final List<String> recommendedActions;

    public OpsAdvice(List<String> summary, List<String> recommendedActions) {
        this.summary = Collections.unmodifiableList(new ArrayList<>(summary));
        this.recommendedActions = Collections.unmodifiableList(new ArrayList<>(recommendedActions));
    }

    public List<String> summary() {
        return summary;
    }

    public List<String> recommendedActions() {
        return recommendedActions;
    }
}
