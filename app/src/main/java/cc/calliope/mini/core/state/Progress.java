package cc.calliope.mini.core.state;

import cc.calliope.mini.core.service.DfuService;

public class Progress {
    public static final int PROGRESS_CONNECTING = DfuService.PROGRESS_CONNECTING;
    public static final int PROGRESS_STARTING = DfuService.PROGRESS_STARTING;
    public static final int PROGRESS_ENABLING_DFU_MODE = DfuService.PROGRESS_ENABLING_DFU_MODE;
    public static final int PROGRESS_VALIDATING = DfuService.PROGRESS_VALIDATING;
    public static final int PROGRESS_DISCONNECTING = DfuService.PROGRESS_DISCONNECTING;
    public static final int PROGRESS_COMPLETED = DfuService.PROGRESS_COMPLETED;
    public static final int PROGRESS_ABORTED = DfuService.PROGRESS_ABORTED;

    private int percent;

    public Progress(int percent) {
        this.percent = percent;
    }

    public int getValue() {
        return percent;
    }

    public void setPercent(int percent) {
        this.percent = percent;
    }

}
