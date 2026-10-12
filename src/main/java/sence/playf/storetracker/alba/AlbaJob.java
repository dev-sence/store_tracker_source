package sence.playf.storetracker.alba;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * 알바에게 맡길 수 있는 작업. GUI에는 {@link #category()}가 TOP인 5개만 1차로 보여주고,
 * OTHER_CROP을 고르면 category가 OTHER인 7개를 2차로 보여준다. 서버에는 항상 최종 선택(구체적인
 * 값, OTHER_CROP 자체는 저장 안 됨)만 전송한다.
 */
public enum AlbaJob {
    PUMPKIN("호박", Items.PUMPKIN, Category.TOP),
    WATERMELON("수박", Items.MELON, Category.TOP),
    PLACE_BLOCK("블럭설치", Items.DIRT, Category.TOP),
    PLANT_SEED("씨앗설치", Items.WHEAT_SEEDS, Category.TOP),
    OTHER_CROP("기타작물", Items.CHEST, Category.TOP),

    NETHER_WART("네더사마귀", Items.NETHER_WART, Category.OTHER),
    SUGAR_CANE("사탕수수", Items.SUGAR_CANE, Category.OTHER),
    COCOA("코코아콩", Items.COCOA_BEANS, Category.OTHER),
    BEETROOT("사탕무", Items.BEETROOT, Category.OTHER),
    WHEAT("밀", Items.WHEAT, Category.OTHER),
    POTATO("감자", Items.POTATO, Category.OTHER),
    CARROT("당근", Items.CARROT, Category.OTHER);

    public enum Category { TOP, OTHER }

    private final String label;
    private final Item icon;
    private final Category category;

    AlbaJob(String label, Item icon, Category category) {
        this.label = label;
        this.icon = icon;
        this.category = category;
    }

    public String label() {
        return label;
    }

    public Item icon() {
        return icon;
    }

    public Category category() {
        return category;
    }

    public static AlbaJob[] topLevel() {
        return new AlbaJob[]{PUMPKIN, WATERMELON, PLACE_BLOCK, PLANT_SEED, OTHER_CROP};
    }

    public static AlbaJob[] otherCrops() {
        return new AlbaJob[]{NETHER_WART, SUGAR_CANE, COCOA, BEETROOT, WHEAT, POTATO, CARROT};
    }

    public static AlbaJob fromName(String name) {
        if (name == null) {
            return null;
        }
        try {
            return AlbaJob.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
