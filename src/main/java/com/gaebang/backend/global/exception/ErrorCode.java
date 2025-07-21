package com.gaebang.backend.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * @author liyusang1
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

    // ITEM
    ITEM_ID_IS_INVALID(HttpStatus.BAD_REQUEST, "잘못된 상품 입니다."),

    // RECIPE
    TITLE_LENGTH_EXCEEDED(HttpStatus.BAD_REQUEST, "레시피 제목이 너무 깁니다."),
    DESCRIPTION_LENGTH_EXCEEDED(HttpStatus.BAD_REQUEST, "레시피 설명이 너무 깁니다."),
    COOKING_TIME_TOO_SHORT(HttpStatus.BAD_REQUEST, "조리 시간은 1분 이상이어야 합니다."),
    RECIPE_ID_IS_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 recipeId 입니다."),

    // RECIPE BOOKMARK
    BOOKMARK_ALREADY_EXITS(HttpStatus.BAD_REQUEST, "이미 북마크된 레시피 입니다."),
    BOOKMARK_NOT_FOUND(HttpStatus.BAD_REQUEST, "북마크를 찾을 수 없습니다"),
    BOOKMARK_ALREADY_DEACTIVATED(HttpStatus.BAD_REQUEST, "이미 북마크가 비활성화 상태입니다."),

    // REPORT
    DUPLICATE_REPORT(HttpStatus.BAD_REQUEST, "이미 신고한 게시글 입니다."),

    // FOLLOW
    SELF_FOLLOW_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "자기 자신을 팔로우할 수 없습니다."),

    // NOTIFICATION
    NOTIFICATION_ID_IS_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 알림id 입니다."),

    // REPLY
    REPLY_ID_IS_INVALID(HttpStatus.BAD_REQUEST, "잘못된 replyId 입니다."),

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
    INTRODUCTION_TOO_LONG(HttpStatus.BAD_REQUEST, "소개는 20자 내로 작성해주세요."),
    NICKNAME_CHANGE_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "닉네임 변경 불가기간(6개월)이 지나지 않았습니다."),

    // restaurant
    ACCESS_REJECT(HttpStatus.FORBIDDEN, "해당 작업에 접근 할 수 없습니다."),
    NOT_LOGIN_STATUS(HttpStatus.FORBIDDEN, "로그인 후 사용 가능합니다."),
    RESTAURANT_NOT_FOUND(HttpStatus.BAD_REQUEST, "요청한 식당이 존재하지 않거나 찾을 수 없습니다."),
    RESTAURANT_LIST_NOT_FOUND(HttpStatus.BAD_REQUEST, "해당 리스트를 찾을 수 없습니다."),
    RESTAURANT_LIST_ACCESS_DENIED(HttpStatus.FORBIDDEN, "요청한 리스트에 대해 접근 할 수 없습니다."),
    THIS_RESTAURANT_PRIVATE(HttpStatus.BAD_REQUEST, "해당 식당정보는 비공개입니다."),


    // 5xx
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 에러");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
