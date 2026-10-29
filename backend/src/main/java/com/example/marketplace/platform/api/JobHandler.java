package com.example.marketplace.platform.api;

public interface JobHandler {
    enum Result { COMPLETE, RETRY, TERMINAL }
    String type();
    Result handle(Jobs.Job job) throws Exception;
}
