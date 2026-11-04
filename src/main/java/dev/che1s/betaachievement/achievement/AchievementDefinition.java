package dev.che1s.betaachievement.achievement;

public final class AchievementDefinition {
    private final String id, name, chineseName, description, parent;
    private final int iconId;
    private final boolean special;
    public AchievementDefinition(String id, String name, String chineseName, String description, int iconId, String parent, boolean special) {
        this.id=id; this.name=name; this.chineseName=chineseName; this.description=description; this.iconId=iconId; this.parent=parent; this.special=special;
    }
    public String getId(){return id;} public String getName(){return name;} public String getChineseName(){return chineseName;} public String getDescription(){return description;} public int getIconId(){return iconId;} public String getParent(){return parent;} public boolean isSpecial(){return special;}
}
