package com.rxtube2.internal;

public class Utils {
    public static <T> void checkNotNull(T t) {
        if (t == null) {
            throw new NullPointerException();
        }
    }
}
