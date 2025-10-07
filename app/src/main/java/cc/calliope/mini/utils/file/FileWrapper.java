package cc.calliope.mini.utils.file;

import java.io.File;

import cc.calliope.mini.ui.model.EditorType;

public record FileWrapper(File file, EditorType editor) {

    public String getName() {
        return file.getName();
    }

    public long lastModified() {
        return file.lastModified();
    }

    public String getAbsolutePath() {
        return file.getAbsolutePath();
    }

    public boolean exists() {
        return file.exists();
    }

    public boolean delete() {
        return file.delete();
    }

    public boolean renameTo(File dest) {
        return file.renameTo(dest);
    }
}
