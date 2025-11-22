package com.hmdp.utils;

public interface ILock {
    boolean trylock(long timeoutSec);

    void unlock();
}
