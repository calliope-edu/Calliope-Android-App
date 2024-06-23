package cc.calliope.mini.state;

import androidx.lifecycle.LiveData;

public class ProgressLiveData extends LiveData<Progress> {

    public void setProgress(int percent){
//        Progress progress = getValue();
//        if(progress != null && progress.getPercent() == percent){
//            return;
//        }
        postValue(new Progress(percent));
    }
}