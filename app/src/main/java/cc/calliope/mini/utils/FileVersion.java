package cc.calliope.mini.utils;

public enum FileVersion {
    UNIVERSAL(2, ":0400000A9900C0DEBB", 0),
    VERSION_2(1, ":020000040000FA", 1),
    VERSION_3(1, ":1000000000040020810A000015070000610A0000BA", 2),
    UNDEFINED(-1, "", -1);

    private final int lineNumber;
    private final String pattern;
    private final int version;

    FileVersion(int lineNumber, String pattern, int version) {
        this.lineNumber = lineNumber;
        this.pattern = pattern;
        this.version = version;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getPattern() {
        return pattern;
    }

    public int getVersion() {
        return version;
    }
}