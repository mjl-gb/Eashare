package com.eashare.utils;

public interface ILock {
    boolean tryLock(long timeoutSec);
    void unLock();
}
