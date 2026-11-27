package com.playerplayback.actor;

/**
 * How an actor looks. Every field is optional; null means "inherit" (from the recording's
 * defaults, or from the original recorded player).
 */
public final class Appearance {
    public String name;
    public String skinValue;
    public String skinSignature;
    /** Human-readable description of where the skin came from (e.g. a username). */
    public String skinSource;
    public Boolean hideNametag;

    public Appearance copy() {
        Appearance a = new Appearance();
        a.name = name;
        a.skinValue = skinValue;
        a.skinSignature = skinSignature;
        a.skinSource = skinSource;
        a.hideNametag = hideNametag;
        return a;
    }

    /** Returns a new appearance where this one's set fields win over {@code base}. */
    public Appearance over(Appearance base) {
        Appearance a = base == null ? new Appearance() : base.copy();
        if (name != null) a.name = name;
        if (skinValue != null) {
            a.skinValue = skinValue;
            a.skinSignature = skinSignature;
            a.skinSource = skinSource;
        }
        if (hideNametag != null) a.hideNametag = hideNametag;
        return a;
    }

    public boolean isEmpty() {
        return name == null && skinValue == null && hideNametag == null;
    }

    public void setSkin(SkinFetcher.Skin skin) {
        if (skin == null) {
            skinValue = null;
            skinSignature = null;
            skinSource = null;
        } else {
            skinValue = skin.value();
            skinSignature = skin.signature();
            skinSource = skin.source();
        }
    }
}
