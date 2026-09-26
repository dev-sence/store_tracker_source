package sence.playf.storetracker.util;

import net.minecraft.client.MinecraftClient;

/** "맵/채널" 식별자. 접속한 서버 주소를 그대로 사용한다 (싱글플레이는 "singleplayer"). */
public final class MapKey {
    public static String current(MinecraftClient client) {
        return client.getCurrentServerEntry() != null
                ? client.getCurrentServerEntry().address
                : "singleplayer";
    }

    private MapKey() {
    }
}
