package cc.calliope.mini.dialog.pattern;

import java.util.LinkedHashMap;
import java.util.Map;

public enum PatternEnum {
        XX(0f),
        ZU(1f),
        VO(2f),
        GI(3f),
        PE(4f),
        TA(5f);

        private final float code;

        PatternEnum(final float code) {
            this.code = code;
        }

        private static final Map<Float, PatternEnum> BY_CODE_MAP = new LinkedHashMap<>();

        static {
            for (PatternEnum pattern : PatternEnum.values()) {
                BY_CODE_MAP.put(pattern.code, pattern);
            }
        }

        public static PatternEnum forCode(float code) {
            return BY_CODE_MAP.get(code);
        }

    }