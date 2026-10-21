package com.pebble.api.report.domain;
public enum ReportTarget {
    POST, PROJECT, POST_COMMENT, PROJECT_COMMENT;
    public boolean isPost(){return this==POST||this==POST_COMMENT;}
    public boolean isComment(){return this==POST_COMMENT||this==PROJECT_COMMENT;}
}
