// IRemoteService.aidl
package com.example.android;

// Declare any non-default types here with import statements.

/** Example service interface */
interface ITest {
    /** Request the process ID of this service. */
    int getPid();

    /** Demonstrates some basic types that you can use as parameters
     * and return values in AIDL.
     */
    void basicTypes(int anInt, long aLong, boolean aBoolean, float aFloat,
            double aDouble, String aString);
}