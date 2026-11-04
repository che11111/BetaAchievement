package dev.che1s.betaachievement.run;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RunSnapshot {
    private final AchievementRun run;
    private final Map<String, RunProgressEntry> progress;

    public RunSnapshot(AchievementRun run, Map<String, RunProgressEntry> progress) {
        this.run = run;
        this.progress = Collections.unmodifiableMap(
            new LinkedHashMap<String, RunProgressEntry>(progress)
        );
    }

    public AchievementRun getRun() { return run; }
    public Map<String, RunProgressEntry> getProgress() { return progress; }
}
