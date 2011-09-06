package com.polopoly.ps.monitor;

public class TestFailedException extends RuntimeException {

    public TestFailedException(Throwable cause) {
        super(cause);
    }

    public TestFailedException(String message) {
        super(message);
    }

}
