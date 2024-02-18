package cc.calliope.mini.dialog.scripts;

import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.apache.commons.io.FilenameUtils;

import java.util.ArrayList;
import java.util.Comparator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import cc.calliope.mini.FileWrapper;
import cc.calliope.mini.R;
import cc.calliope.mini.utils.Utils;

public class ScriptsRecyclerAdapter extends RecyclerView.Adapter<ScriptsRecyclerAdapter.ViewHolder> {
    private final ArrayList<FileWrapper> files;
    private OnItemClickListener onItemClickListener;
    private OnItemLongClickListener onItemLongClickListener;

    public interface OnItemClickListener {
        void onItemClick(FileWrapper file);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(View view, FileWrapper file);
    }

    public ScriptsRecyclerAdapter(ArrayList<FileWrapper> files) {
        this.files = files;
        sort();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.onItemClickListener = listener;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener listener) {
        this.onItemLongClickListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.scripts_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (files == null)
            return;

        FileWrapper file = files.get(position);
        holder.setItem(file);
        if (onItemClickListener != null) {
            holder.itemView.setOnClickListener(view -> onItemClickListener.onItemClick(file));
        }

        if(onItemLongClickListener != null){
            holder.itemView.setOnLongClickListener(view -> {
                onItemLongClickListener.onItemLongClick(view, file);
                return false;
            });
        }
    }

    public boolean isEmpty(){
        return files.isEmpty();
    }

    public void remove(FileWrapper file) {
        int index = files.indexOf(file);
        if(files.remove(file)) {
            notifyItemRemoved(index);
        }
    }

    public void change(FileWrapper oldFile, FileWrapper newFile){
        int index = files.indexOf(oldFile);
        if(files.remove(oldFile)) {
            files.add(index, newFile);
            notifyItemChanged(index);
            sort();
        }
    }

    public void sort(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            files.sort(new CustomComparator());
        }
    }

    static class CustomComparator implements Comparator<FileWrapper> {
        @Override
        public int compare(FileWrapper file1, FileWrapper file2) {
            return Long.compare(file2.lastModified(), file1.lastModified());
        }
    }

    @Override
    public int getItemCount() {
        if (files == null) {
            return 0;
        } else {
            return files.size();
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder{
        private final TextView name;
        private final TextView date;
        private final TextView version;
        private final ImageView icon;

        private ViewHolder(View view) {
            super(view);

            name = view.findViewById(R.id.hex_file_name_text_view);
            date = view.findViewById(R.id.hex_file_date_text_view);
            version = view.findViewById(R.id.hex_file_version_text_view);
            icon = view.findViewById(R.id.hex_file_icon);
        }

        void setItem(FileWrapper file) {
            String name = FilenameUtils.removeExtension(file.getName());
            String date = Utils.dateFormat(file.lastModified());
            String version = "v" + Utils.getFileVersion(file.getAbsolutePath());

            this.name.setText(name);
            this.date.setText(date);
            this.version.setText(version);
            this.icon.setImageResource(file.editor().getIconResId());
        }
    }
}