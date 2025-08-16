package com.gaebang.backend.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * @implNote 에러 메시지 코드들을 한번에 관리
 */

@Getter
public enum ErrorCode {

    // Permission
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, "해당 작업을 수행할 권한이 없습니다."),

    // S3
    S3_ERROR(HttpStatus.FORBIDDEN, "이미지 업로드 중 오류가 발생했습니다."),

    // EMAIL
    EMAIL_SENDING_FAILURE(HttpStatus.INTERNAL_SERVER_ERROR, "이메일 전송에 실패했습니다."),
    EMAIL_TEMPLATE_LOAD_FAILURE(HttpStatus.INTERNAL_SERVER_ERROR, "이메일 템플릿 로드에 실패했습니다."),
    EMAIL_VERIFY_FAILURE(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않습니다."),

    // USER
    USER_NOT_FOUND(HttpStatus.BAD_REQUEST, "존재하지 않는 회원입니다."),
    USER_ALREADY_REGISTERED(HttpStatus.BAD_REQUEST, "이미 가입된 회원입니다."),
    USER_INVALID_ACCESS(HttpStatus.BAD_REQUEST, "잘못된 유저의 접근입니다."),
    POINT_TIER_IS_NOT_EXIST(HttpStatus.BAD_REQUEST, "포인트 티어 정보가 DB에 초기화 되지 않았습니다."),

    // REPORT
    DUPLICATE_REPORT(HttpStatus.BAD_REQUEST, "이미 신고한 게시글 입니다."),

    // FREEBOARD
    FREE_BOARD_ID_IS_INVALID(HttpStatus.BAD_REQUEST, "잘못된 freeBoardId 입니다."),

    // FREEBOARDREPLY
    FREE_BOARD_REPLY_ID_IS_INVALID(HttpStatus.BAD_REQUEST, "잘못된 freeBoardReplyId 입니다."),

    // AUTH
    INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "비밀번호가 틀렸습니다."),

    // myPage
    CURRENT_PASSWORD_NOT_MATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 일치하지 않습니다."),
    NEW_PASSWORD_SAME_AS_OLD(HttpStatus.BAD_REQUEST, "새 비밀번호가 기존 비밀번호와 동일합니다."),
    NEW_PASSWORD_NOT_MATCH(HttpStatus.BAD_REQUEST, "변경을 위해 입력하신 비밀번호와 다릅니다."),
    EMAIL_NOT_MATCH(HttpStatus.BAD_REQUEST, "이메일이 일치하지 않습니다"),
    NICKNAME_ALREADY_EXISTS(HttpStatus.BAD_REQUEST, "중복된 닉네임입니다."),
    NICKNAME_CHANGE_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "닉네임 변경 불가기간(6개월)이 지나지 않았습니다."),

    // PAYMENT
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "진행 중인 결제를 찾을 수 없습니다"),
    PAYMENT_ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 결제입니다"),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 일치하지 않습니다"),
    PAYMENT_CANCELLED(HttpStatus.BAD_REQUEST, "결제가 취소되었습니다"),
    PAYMENT_FAILED(HttpStatus.BAD_REQUEST, "결제가 실패하였습니다"),
    PAYMENT_EXTERNAL_API_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "외부 결제 API 오류가 발생했습니다"),

    // POINT
    INSUFFICIENT_POINT(HttpStatus.BAD_REQUEST, "남은 포인트가 사용하고자 하는 포인트보다 부족합니다."),
    POINT_CREATION_RETRY_EXHAUSTED_EXCEPTION(HttpStatus.INTERNAL_SERVER_ERROR, "포인트 생성에 실패했습니다. 잠시 후 다시 시도해주세요."),
    // restaurant
    ACCESS_REJECT(HttpStatus.FORBIDDEN, "해당 작업에 접근 할 수 없습니다."),
    NOT_LOGIN_STATUS(HttpStatus.FORBIDDEN, "로그인 후 사용 가능합니다."),
    RESTAURANT_NOT_FOUND(HttpStatus.BAD_REQUEST, "요청한 식당이 존재하지 않거나 찾을 수 없습니다."),
    RESTAURANT_LIST_NOT_FOUND(HttpStatus.BAD_REQUEST, "해당 리스트를 찾을 수 없습니다."),
    RESTAURANT_LIST_ACCESS_DENIED(HttpStatus.FORBIDDEN, "요청한 리스트에 대해 접근 할 수 없습니다."),
    THIS_RESTAURANT_PRIVATE(HttpStatus.BAD_REQUEST, "해당 식당정보는 비공개입니다."),

    // Conversation
    CONVERSATION_NOT_FOUND(HttpStatus.BAD_REQUEST, "대화방을 찾을 수 없습니다."),

    // board
    BOARD_NOT_FOUND(HttpStatus.BAD_REQUEST, "해당 게시글을 찾을 수 없습니다."),

    // comment
    COMMENT_NOT_FOUND(HttpStatus.BAD_REQUEST, "해당 댓글을 찾을 수 없습니다."),

    // boardReport
    BOARD_REPORT_NOT_FOUND(HttpStatus.BAD_REQUEST, "해당 게시글 신고를 찾을 수 없습니다."),

    // 5xx
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 에러");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
