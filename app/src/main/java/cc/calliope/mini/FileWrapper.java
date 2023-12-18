package cc.calliope.mini;

import java.io.File;

import cc.calliope.mini.fragment.editors.Editor;

public class FileWrapper {
    private final File file;
    private final Editor editor;

    public FileWrapper(File file, Editor editor) {
        this.file = file;
        this.editor = editor;
    }

    public File getFile() {
        return file;
    }

    public Editor getEditor() {
        return editor;
    }

    public String getName(){
        return file.getName();
    }

    public long lastModified(){
        return file.lastModified();
    }

    public String getAbsolutePath(){
        return file.getAbsolutePath();
    }

    public boolean exists(){
        return file.exists();
    }

    public boolean delete(){
        return file.delete();
    }

    public boolean renameTo(File dest){
        return file.renameTo(dest);
    }
}
