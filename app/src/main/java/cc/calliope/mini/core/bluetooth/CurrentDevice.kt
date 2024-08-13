package cc.calliope.mini.core.bluetooth

import android.os.Parcel
import android.os.Parcelable

data class CurrentDevice(
    var address: String,
    var pattern: String
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(address)
        parcel.writeString(pattern)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<CurrentDevice> {
        override fun createFromParcel(parcel: Parcel): CurrentDevice = CurrentDevice(parcel)

        override fun newArray(size: Int): Array<CurrentDevice?> = arrayOfNulls(size)
    }
}
