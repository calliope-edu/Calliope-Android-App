package cc.calliope.mini.utils;

import android.os.Build;

public class Version {
    public static final boolean VERSION_M_AND_NEWER = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    public static final boolean VERSION_N_AND_NEWER = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    public static final boolean VERSION_O_AND_NEWER = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    public static final boolean VERSION_Q_AND_NEWER = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    public static final boolean VERSION_S_AND_NEWER = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    public static final boolean VERSION_TIRAMISU_AND_NEWER = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
}
