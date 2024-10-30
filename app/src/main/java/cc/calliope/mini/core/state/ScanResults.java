package cc.calliope.mini.core.state;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.jetbrains.annotations.NotNull;

import java.util.List;

import cc.calliope.mini.core.bluetooth.Device;

public class ScanResults {
    private static final MutableLiveData<List<Device>> scanLiveData = new MutableLiveData<>();

    public void updateResults(@NotNull List<Device> devices) {
        scanLiveData.postValue(devices);
    }

    public static LiveData<List<Device>> getStateLiveData() {
        return scanLiveData;
    }
}
