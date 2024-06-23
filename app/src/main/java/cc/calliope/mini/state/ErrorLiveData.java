package cc.calliope.mini.state;

import androidx.lifecycle.LiveData;

public class ErrorLiveData extends LiveData<Error> {

    public void setError(int code, String message){
//        Error error = getValue();
//        if(error != null && error.getCode() == code && error.getMessage().equals(message)){
//            return;
//        }
        postValue(new Error(code, message));
    }
}