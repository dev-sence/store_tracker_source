package sence.playf.storetracker.alba;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

/** 호박 알바를 고용할 때만 고르는 작업 구역 4개 - 섬 알바동 유리판 색깔(핑크/노랑/보라/파랑)과 맞춘다.
 * 아이콘(유리판 색)은 고정이고, 라벨 문구만 설정화면에서 바꿀 수 있다. */
public enum AlbaZone {
    PINK(Items.PINK_STAINED_GLASS_PANE),
    YELLOW(Items.YELLOW_STAINED_GLASS_PANE),
    PURPLE(Items.PURPLE_STAINED_GLASS_PANE),
    BLUE(Items.LIGHT_BLUE_STAINED_GLASS_PANE);

    private final Item icon;

    AlbaZone(Item icon) {
        this.icon = icon;
    }

    public Item icon() {
        return icon;
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
