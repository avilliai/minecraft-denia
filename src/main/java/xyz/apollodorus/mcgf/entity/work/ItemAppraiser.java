package xyz.apollodorus.mcgf.entity.work;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.*;

/**
 * ?????????????????????????????????????????
 */
public final class ItemAppraiser {
    private ItemAppraiser() {}

    public enum ItemCategory {
        WEAPON, ARMOR, TOOL, FOOD, ORE_GEM, VOID_MAGIC, BUILDING, UTILITY, JUNK
    }

    public record Evaluation(
            ItemCategory category,
            int score,              // 0 ~ 100 ???
            int daniyaPreference,   // -100(????) ~ +100(????)
            String qualityDescription,
            String daniyaComment
    ) {}

    private static final Set<String> DISLIKED_KEYS = Set.of(
            "rotten_flesh", "poisonous_potato", "spider_eye", "fermented_spider_eye",
            "toxic", "waste", "filth", "sludge", "dung"
    );

    private static final Set<String> SWEET_KEYS = Set.of(
            "cake", "cookie", "pie", "sweet_berries", "honey", "honeycomb",
            "apple", "golden_apple", "sugar", "pudding", "chocolate", "candy",
            "ice_cream", "pastry", "donut", "bread"
    );

    private static final Set<String> SEA_KEYS = Set.of(
            "seal", "fish", "salmon", "cod", "nautilus_shell", "heart_of_the_sea",
            "prismarine", "sponge", "coral", "kelp", "water_bucket"
    );

    private static final Set<String> VOID_COSMIC_KEYS = Set.of(
            "ender_pearl", "eye_of_ender", "nether_star", "echo_shard", "amethyst",
            "shulker", "void", "star", "astral", "singularity", "quantum", "frequency",
            "resonator", "dark_matter", "spatial", "crystal"
    );

    public static Evaluation evaluate(ItemStack stack, GirlfriendEntity gf) {
        if (stack == null || stack.isEmpty()) {
            return new Evaluation(ItemCategory.JUNK, 0, 0, "??", "???????");
        }

        Item item = stack.getItem();
        Identifier id = Registries.ITEM.getId(item);
        String path = id.getPath().toLowerCase(Locale.ROOT);
        String ns = id.getNamespace().toLowerCase(Locale.ROOT);

        int baseScore = 10;
        int preference = 0;
        ItemCategory cat = ItemCategory.UTILITY;
        String comment = null;

        for (String bad : DISLIKED_KEYS) {
            if (path.contains(bad)) {
                return new Evaluation(ItemCategory.JUNK, 5, -80, "??/????", "?????????????????????");
            }
        }

        for (String sweet : SWEET_KEYS) {
            if (path.contains(sweet)) {
                preference += 60;
                comment = "???????????????????????????";
                break;
            }
        }

        for (String sea : SEA_KEYS) {
            if (path.contains(sea)) {
                preference += 45;
                if (comment == null) comment = "??????????????????????????";
                break;
            }
        }

        for (String cosmic : VOID_COSMIC_KEYS) {
            if (path.contains(cosmic)) {
                preference += 55;
                if (comment == null) comment = "?????????????????????????????";
                break;
            }
        }

        EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        if (equippable != null && isWearableArmorSlot(equippable.slot())) {
            cat = ItemCategory.ARMOR;
            baseScore = 35 + getArmorRating(stack);
        } else if (isWeaponLike(stack, path)) {
            cat = ItemCategory.WEAPON;
            baseScore = 40 + (int)(getWeaponDamage(stack) * 6);
            if (stack.hasEnchantments()) baseScore += 15;
        } else if (isToolLike(path)) {
            cat = ItemCategory.TOOL;
            baseScore = 40 + getTierBonus(path);
            if (stack.hasEnchantments()) baseScore += 15;
        } else if (stack.contains(DataComponentTypes.FOOD)) {
            cat = ItemCategory.FOOD;
            FoodComponent food = stack.get(DataComponentTypes.FOOD);
            int nutrition = food != null ? food.nutrition() : 2;
            baseScore = Math.min(100, 20 + nutrition * 10);
            if (preference == 0) preference = 20;
        } else if (isValuableMineral(path, ns)) {
            cat = ItemCategory.ORE_GEM;
            baseScore = getMineralScore(path);
            preference += 30;
            if (comment == null) comment = "??????????????????????????";
        } else if (item instanceof BlockItem) {
            cat = ItemCategory.BUILDING;
            baseScore = 25;
        }

        Rarity rarity = stack.getRarity();
        if (rarity == Rarity.UNCOMMON) baseScore += 15;
        else if (rarity == Rarity.RARE) baseScore += 25;
        else if (rarity == Rarity.EPIC) baseScore += 40;

        int finalScore = Math.max(0, Math.min(100, baseScore));
        int finalPref = Math.max(-100, Math.min(100, preference));

        String qualityDesc = finalScore >= 85 ? "??/??" :
                finalScore >= 65 ? "??/??" :
                finalScore >= 40 ? "??/??" : "??/??";

        if (comment == null) {
            if (finalScore >= 80) comment = "????????????????????";
            else if (finalScore >= 50) comment = "????????????????";
            else comment = "?????????????????????";
        }

        return new Evaluation(cat, finalScore, finalPref, qualityDesc, comment);
    }

    private static boolean isWearableArmorSlot(EquipmentSlot slot) {
        return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
                || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
    }

    private static boolean isWeaponLike(ItemStack stack, String path) {
        return path.contains("sword") || path.contains("blade") || path.contains("axe")
                || path.contains("bow") || path.contains("spear") || path.contains("trident")
                || path.contains("wand") || path.contains("staff") || path.contains("dagger");
    }

    private static boolean isToolLike(String path) {
        return path.contains("pickaxe") || path.contains("shovel") || path.contains("hoe")
                || path.contains("shears") || path.contains("fishing_rod") || path.contains("wrench");
    }

    private static int getTierBonus(String path) {
        if (path.contains("netherite")) return 35;
        if (path.contains("diamond")) return 28;
        if (path.contains("iron")) return 18;
        if (path.contains("gold")) return 14;
        if (path.contains("stone")) return 10;
        return 5;
    }

    private static boolean isValuableMineral(String path, String ns) {
        return path.contains("diamond") || path.contains("netherite") ||
                path.contains("emerald") || path.contains("ancient_debris") ||
                path.contains("gold_ingot") || path.contains("iron_ingot") ||
                path.contains("copper_ingot") || path.contains("redstone") ||
                path.contains("lapis") || path.contains("ingot") || path.contains("gem");
    }

    private static int getMineralScore(String path) {
        if (path.contains("netherite") || path.contains("ancient_debris")) return 95;
        if (path.contains("diamond")) return 85;
        if (path.contains("emerald")) return 75;
        if (path.contains("gold")) return 65;
        if (path.contains("iron")) return 55;
        if (path.contains("lapis") || path.contains("redstone")) return 50;
        return 40;
    }

    public static boolean isBetterEquipment(ItemStack newStack, ItemStack oldStack, EquipmentSlot slot) {
        if (newStack == null || newStack.isEmpty()) return false;
        if (oldStack == null || oldStack.isEmpty()) return true;

        if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
            int newProt = getArmorRating(newStack);
            int oldProt = getArmorRating(oldStack);
            if (newProt != oldProt) return newProt > oldProt;
        } else if (slot == EquipmentSlot.MAINHAND) {
            float newDmg = getWeaponDamage(newStack);
            float oldDmg = getWeaponDamage(oldStack);
            if (Math.abs(newDmg - oldDmg) > 0.1f) return newDmg > oldDmg;
        }
        return newStack.getRarity().ordinal() > oldStack.getRarity().ordinal();
    }

    public static int getArmorRating(ItemStack stack) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        int base = 0;
        if (path.contains("netherite")) base = 35;
        else if (path.contains("diamond")) base = 28;
        else if (path.contains("iron")) base = 18;
        else if (path.contains("chainmail")) base = 14;
        else if (path.contains("golden")) base = 12;
        else if (path.contains("leather")) base = 8;
        else base = 10;

        if (stack.hasEnchantments()) base += 6;
        return base;
    }

    public static float getWeaponDamage(ItemStack stack) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        float base = 1.0f;
        if (path.contains("netherite")) base = 8.0f;
        else if (path.contains("diamond")) base = 7.0f;
        else if (path.contains("iron")) base = 6.0f;
        else if (path.contains("golden")) base = 4.0f;
        else if (path.contains("stone")) base = 5.0f;
        else if (path.contains("wooden")) base = 4.0f;

        if (path.contains("axe")) base += 2.0f;
        if (stack.hasEnchantments()) base += 2.0f;
        return base;
    }

    public static void reactToAcquisition(GirlfriendEntity gf, ItemStack stack) {
        if (gf == null || stack == null || stack.isEmpty()) return;
        Evaluation eval = evaluate(stack, gf);
        if (eval.daniyaPreference() >= 50) {
            xyz.apollodorus.mcgf.ai.MoodManager.tryEventProactive(gf, "?????????: " + stack.getName().getString() + "????????????????");
            if (gf.getRandom().nextFloat() < 0.4f) {
                xyz.apollodorus.mcgf.ai.SpeechBus.speak(gf, eval.daniyaComment());
            }
        } else if (eval.daniyaPreference() <= -40) {
            xyz.apollodorus.mcgf.ai.MoodManager.tryEventProactive(gf, "???????????: " + stack.getName().getString() + "?????");
            if (gf.getRandom().nextFloat() < 0.3f) {
                xyz.apollodorus.mcgf.ai.SpeechBus.speak(gf, eval.daniyaComment());
            }
        }
    }

    public static double evaluateItemScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0.0;
        Evaluation ev = evaluate(stack, null);
        return ev.score() + (ev.daniyaPreference() * 0.2);
    }


    public static String summarizeInventory(GirlfriendEntity gf) {
        StringBuilder sb = new StringBuilder("达妮娅随身背包品质与偏好评估：");
        boolean any = false;
        for (int i = 0; i < gf.getInventory().size(); i++) {
            ItemStack stack = gf.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            Evaluation ev = evaluate(stack, gf);
            if (ev.score() >= 60 || Math.abs(ev.daniyaPreference()) >= 40) {
                any = true;
                sb.append("[").append(stack.getName().getString()).append(": ")
                  .append(ev.qualityDescription()).append(", ")
                  .append(ev.daniyaComment()).append("] ");
            }
        }
        return any ? sb.toString() : "随身物品普通，暂无稀有珍品或极具偏好之物。";
    }

}
