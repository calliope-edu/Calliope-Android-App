package cc.calliope.mini.state;

public class StateManager {

    private static final StateLiveData stateLiveData = new StateLiveData();

    public static void updateState(int type, String message){
        stateLiveData.setState(type, message);
    }

    public static StateLiveData getStateLiveData() {
        return stateLiveData;
    }
}