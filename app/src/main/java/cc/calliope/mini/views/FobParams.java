package cc.calliope.mini.views;

import android.os.Parcel;
import android.os.Parcelable;

public class FobParams implements Parcelable {
    int w;
    int h;
    float x;
    float y;

    public static final Creator<FobParams> CREATOR = new Creator<FobParams>() {
        @Override
        public FobParams createFromParcel(Parcel in) {
            return new FobParams(in);
        }

        @Override
        public FobParams[] newArray(int size) {
            return new FobParams[size];
        }
    };

    public FobParams(int w, int h, float x, float y) {
        this.w = w;
        this.h = h;
        this.x = x;
        this.y = y;
    }

    private FobParams(Parcel in) {
        this.w = in.readInt();
        this.h = in.readInt();
        this.x = in.readFloat();
        this.y = in.readFloat();
    }

    public int getW() {
        return w;
    }

    public int getH() {
        return h;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public int getCenterX() {
        return Math.round(x) + w / 2;
    }

    public int getCenterY() {
        return Math.round(y) + h / 2;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(w);
        dest.writeInt(h);
        dest.writeFloat(x);
        dest.writeFloat(y);
    }

}