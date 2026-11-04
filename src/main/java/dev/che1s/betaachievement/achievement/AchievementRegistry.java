package dev.che1s.betaachievement.achievement;
import java.util.*;
/** Exact IDs, graph and icon metadata from Beta 1.7.3 AchievementList. */
public final class AchievementRegistry {
 private final Map<String,AchievementDefinition> byId=new LinkedHashMap<String,AchievementDefinition>();
 public AchievementRegistry(){
  add("openInventory","Taking Inventory","打开物品栏","Press 'E' to open your inventory.",340,null,false);
  add("mineWood","Getting Wood","获得木头","Attack a tree until a block of wood pops out.",17,"openInventory",false);
  add("buildWorkBench","Benchmarking","这是？工作台！","Craft a workbench with four blocks of planks.",58,"mineWood",false);
  add("buildPickaxe","Time to Mine!","采矿时间到！","Use planks and sticks to make a pickaxe.",270,"buildWorkBench",false);
  add("buildFurnace","Hot Topic","“热”门话题","Construct a furnace out of eight stone blocks.",61,"buildPickaxe",false);
  add("acquireIron","Acquire Hardware","来硬的","Smelt an iron ingot.",265,"buildFurnace",false);
  add("buildHoe","Time to Farm!","耕种时间到！","Use planks and sticks to make a hoe.",290,"buildWorkBench",false);
  add("makeBread","Bake Bread","烤面包","Turn wheat into bread.",297,"buildHoe",false);
  add("bakeCake","The Lie","蛋糕是个谎言","Wheat, sugar, milk, and eggs!",354,"buildHoe",false);
  add("buildBetterPickaxe","Getting an Upgrade","获得升级","Construct a better pickaxe.",274,"buildPickaxe",false);
  add("cookFish","Delicious Fish","美味的鱼儿","Catch and cook a fish!",350,"buildFurnace",false);
  add("onARail","On A Rail","在铁路上","Travel by minecart to a point at least 1 km away.",66,"acquireIron",true);
  add("buildSword","Time to Strike!","出击时间到！","Use planks and sticks to make a sword.",268,"buildWorkBench",false);
  add("killEnemy","Monster Hunter","怪物猎人","Attack and destroy a monster.",352,"buildSword",false);
  add("killCow","Cow Tipper","斗牛士","Harvest some leather.",334,"buildSword",false);
  add("flyPig","When Pigs Fly","当猪飞的时候","Fly a pig off a cliff.",329,"killCow",true);
 }
 private void add(String i,String n,String c,String d,int icon,String p,boolean s){byId.put(i,new AchievementDefinition(i,n,c,d,icon,p,s));}
 public AchievementDefinition get(String id){return byId.get(id);} public Collection<AchievementDefinition> all(){return Collections.unmodifiableCollection(byId.values());}
 public Set<String> knownOnly(Collection<String> ids){
  Set<String> known=new LinkedHashSet<String>();
  for(String id:ids)if(byId.containsKey(id))known.add(id);
  return known;
 }
}
