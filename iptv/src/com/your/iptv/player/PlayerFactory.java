package com.your.iptv.player;

import android.content.Context;

import com.your.iptv.IPlayer;

/** 引擎工厂:创建 + 自动降级。ExoPlayerImpl 通过反射探测(未引入依赖时优雅缺失)。 */
public final class PlayerFactory {

    private PlayerFactory() {}

    public static boolean exoAvailable() {
        try {
            Class.forName("com.google.android.exoplayer2.SimpleExoPlayer");
            return true;
        } catch (Throwable t) { return false; }
    }

    /** @param preferExo true=优先 Exo(兜底系统);false=优先系统(兜底 Exo) */
    public static IPlayer create(Context ctx, boolean preferExo) {
        if (preferExo) {
            IPlayer p = createExo(ctx);
            if (p != null) return p;
        }
        return new SystemPlayerImpl(ctx);
    }

    private static IPlayer createExo(Context ctx) {
        if (!exoAvailable()) return null;
        try {
            return (IPlayer) Class.forName("com.your.iptv.player.ExoPlayerImpl")
                    .getConstructor(Context.class)
                    .newInstance(ctx);
        } catch (Throwable t) { return null; }
    }
}
