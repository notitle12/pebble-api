package com.pebble.api.report.domain;
import com.pebble.api.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;
public enum ReportError implements ErrorCode {
    REPORT_ALREADY_HANDLED;
    public HttpStatus status(){return HttpStatus.CONFLICT;}
    public String code(){return name();}
    public String message(){return "이미 다른 조치로 처리한 신고입니다. 목록을 새로 확인해 주세요.";}
}
