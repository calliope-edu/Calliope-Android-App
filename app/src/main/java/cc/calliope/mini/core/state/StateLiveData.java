package cc.calliope.mini.core.state;

import androidx.lifecycle.LiveData;

public class StateLiveData extends LiveData<State> {

    public void setState(int type){
//        State state = getValue();
//        if(state != null && state.getType() == type){
//            return;
//        }
        postValue(new State(type));
    }
}