package com.example.marketplace.operations.domain;

public enum ReportTransition {
    START("RECEIVED","INVESTIGATING"), CLOSE("INVESTIGATING","CLOSED"), REOPEN("CLOSED","INVESTIGATING");
    private final String from;
    private final String to;
    ReportTransition(String from,String to){this.from=from;this.to=to;}
    public String apply(String current){
        if(!from.equals(current)){throw new IllegalStateException("REPORT_STATE_CONFLICT");}
        return to;
    }
}
