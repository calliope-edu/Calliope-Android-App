package cc.calliope.mini.state;

import androidx.lifecycle.LiveData;

public class StateLiveData extends LiveData<State> {

    public void setState(int type, String message){
        postValue(new State(type, message));
    }
}