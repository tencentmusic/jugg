package com.sickworm.jugg.demo.testcase.genericcaller;

public class GenericInvoker {

    public String invoke() {
        StringHolder holder = new StringHolder();
        holder.set("demo");
        return holder.get();
    }
}
