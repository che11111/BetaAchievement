package dev.che1s.betaachievement.achievement;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Item IDs used by achievement triggers, verified against Beta 1.7.3 Item.java. */
public final class AchievementTriggers {
    public static final Set<Integer> PICKAXES = ids(257, 270, 274, 278, 285);
    public static final Set<Integer> BETTER_PICKAXES = ids(257, 274, 278, 285);
    public static final Set<Integer> HOES = ids(290, 291, 292, 293, 294);
    public static final Set<Integer> SWORDS = ids(267, 268, 272, 276, 283);

    private AchievementTriggers() {
    }

    private static Set<Integer> ids(Integer... values) {
        return Collections.unmodifiableSet(new HashSet<Integer>(Arrays.asList(values)));
    }

    public static boolean isPickaxe(int itemId) {
        return PICKAXES.contains(itemId);
    }

    public static boolean isBetterPickaxe(int itemId) {
        return BETTER_PICKAXES.contains(itemId);
    }

    public static boolean isHoe(int itemId) {
        return HOES.contains(itemId);
    }

    public static boolean isSword(int itemId) {
        return SWORDS.contains(itemId);
    }
}
