package cc.calliope.mini.datamodel;

import java.io.File;

public class HexFile {
    public File File;
    public long lastModified;

    public HexFile(File File, long lastModified) {
        this.File = File;
        this.lastModified = lastModified;
    }
}