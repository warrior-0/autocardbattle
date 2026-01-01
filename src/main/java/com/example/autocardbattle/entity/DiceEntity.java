package com.example.game.dice;

public enum DiceType {
    FIRE("불", 40, 2, 0.5, "광역 데미지"),
    WIND("바람", 15, 3, 2.0, "고속 공격"),
    SWORD("검", 80, 1, 0.8, "강력 일격"),
    ELECTRIC("전기", 30, 2, 1.0, "연쇄 피해"),
    SNIPER("저격", 25, 4, 0.7, "거리 비례 보너스");

    private final String name;
    private final int damage;
    private final int range;
    private final double aps;
    private final String desc;

    DiceType(String name, int damage, int range, double aps, String desc) {
        this.name = name;
        this.damage = damage;
        this.range = range;
        this.aps = aps;
        this.desc = desc;
    }

    // JS로 데이터를 보낼 때 필요한 Getter들
    public String getName() { return name; }
    public int getDamage() { return damage; }
    public int getRange() { return range; }
    public double getAps() { return aps; }
    public String getDesc() { return desc; }
}
