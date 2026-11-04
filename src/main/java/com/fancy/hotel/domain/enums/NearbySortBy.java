package com.fancy.hotel.domain.enums;

import lombok.Getter;

/**
 * 附近酒店的排序方式
 */
@Getter
public enum NearbySortBy {

    /** 距离升序（GEO 天然就是这个顺序） */
    DISTANCE("distance", "距离"),

    /** 评分降序 */
    RATING("rating", "评分"),

    /** 最低价升序 */
    PRICE("price", "价格");

    private final String code;
    private final String desc;

    NearbySortBy(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static NearbySortBy parse(String s) {
        if (s == null || s.isBlank()) {
            return DISTANCE;
        }
        for (NearbySortBy v : values()) {
            if (v.code.equalsIgnoreCase(s.trim())) {
                return v;
            }
        }
        throw new IllegalArgumentException("sortBy 只能是 distance / rating / price，收到: " + s);
    }
}
