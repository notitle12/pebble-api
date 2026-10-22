package com.pebble.api.member.domain;

import com.pebble.api.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum MemberError implements ErrorCode {
    SNS_AGE_CONFIRMATION_REQUIRED(HttpStatus.FORBIDDEN, "SNS의 만 14세 이상 가입 제한 적용을 확인할 수 없어 신규 가입을 진행할 수 없습니다. 다시 로그인해 주세요."),
    AGREEMENTS_REQUIRED(HttpStatus.FORBIDDEN, "서비스 이용약관과 개인정보 수집·이용에 동의해 주세요."),
    AGREEMENT_VERSION_CHANGED(HttpStatus.CONFLICT, "약관이 변경되었습니다. 내용을 다시 확인해 주세요."),
    LEGAL_DOCUMENTS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "가입 안내를 준비하고 있어요. 잠시 후 다시 시도해 주세요."),
    VISIT_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "방문 집계 요청이 많습니다. 잠시 후 다시 시도해 주세요."),
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "정지된 계정은 이용할 수 없습니다."),
    ACCOUNT_WITHDRAWAL_PENDING(HttpStatus.FORBIDDEN, "탈퇴 대기 중인 계정은 이용할 수 없습니다."),
    STONE_LAYOUT_CHANGED(HttpStatus.CONFLICT, "다른 화면에서 돌 배치가 변경되었습니다. 최신 배치를 다시 불러와 주세요."),
    WITHDRAWAL_PENDING(HttpStatus.CONFLICT, "탈퇴 대기 회원의 상태는 관리자가 변경할 수 없습니다."),
    WITHDRAWAL_NOT_PENDING(HttpStatus.CONFLICT, "탈퇴 예약 상태인 회원만 취소할 수 있습니다."),
    WITHDRAWAL_EXPIRED(HttpStatus.CONFLICT, "탈퇴 예약 취소 기한이 지났습니다."),
    PROFILE_REQUIRED(HttpStatus.CONFLICT, "블로그명과 공개 아이디를 먼저 설정해 주세요."),
    PROFILE_ALREADY_COMPLETED(HttpStatus.CONFLICT, "최초 설정을 이미 완료했습니다."),
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    DUPLICATE_BLOG_NAME(HttpStatus.CONFLICT, "이미 사용 중인 블로그명입니다."),
    DUPLICATE_HANDLE(HttpStatus.CONFLICT, "이미 사용 중인 공개 아이디입니다."),
    NICKNAME_CHANGE_COOLDOWN(HttpStatus.CONFLICT, "닉네임은 마지막 설정 후 7일이 지나야 변경할 수 있습니다."),
    BLOG_NAME_CHANGE_COOLDOWN(HttpStatus.CONFLICT, "블로그명은 마지막 설정 후 7일이 지나야 변경할 수 있습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public String message() {
        return message;
    }
}
