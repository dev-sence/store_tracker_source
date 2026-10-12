package sence.playf.storetracker.alba;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

/** 호박 알바를 고용할 때만 고르는 작업 구역 4개 - 섬 알바동 유리판 색깔(핑크/노랑/보라/파랑)과 맞춘다.
 * 아이콘(유리판 색)은 고정이고, 라벨 문구만 설정화면에서 바꿀 수 있다. */
public enum AlbaZone {
    PINK(Items.PINK_STAINED_GLASS_PANE, 0xFFF783C7),
    YELLOW(Items.YELLOW_STAINED_GLASS_PANE, 0xFFF7E017),
    PURPLE(Items.PURPLE_STAINED_GLASS_PANE, 0xFFA23CC9),
    BLUE(Items.LIGHT_BLUE_STAINED_GLASS_PANE, 0xFF4EC9F7);

    private final Item icon;
    private final int color;

    AlbaZone(Item icon, int color) {
        this.icon = icon;
        this.color = color;
    }

    public Item icon() {
        return icon;
    }

    /** HUD에 작은 색상 네모로 구역을 바로 알아볼 수 있게 - 0xAARRGGBB. */
    public int color() {
        return color;
    }

    public static String defaultLabel(AlbaZone zone) {
        return switch (zone) {
            case PINK -> "핑크 (왼쪽 알바동)";
            case YELLOW -> "노랑 (왼쪽 알바동)";
            case PURPLE -> "보라 (오른쪽 알바동)";
            case BLUE -> "파랑 (오른쪽 알바동)";
        };
    }
}
