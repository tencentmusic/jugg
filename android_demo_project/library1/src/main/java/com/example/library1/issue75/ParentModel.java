package com.example.library1.issue75;

import android.os.Parcel;
import android.os.Parcelable;

public class ParentModel implements Parcelable {
    public long _id;
    public String title;
    public int describeContents() { return 0; }
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(_id);
        dest.writeString(title);
    }
}
