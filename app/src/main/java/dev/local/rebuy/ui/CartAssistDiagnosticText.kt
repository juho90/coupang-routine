package dev.local.rebuy.ui

import dev.local.rebuy.cart.CartAssistDiagnostics
import dev.local.rebuy.cart.CartAssistStage
import dev.local.rebuy.cart.CartAssistStopReason

/** Only diagnostic enums, counts and flags; no product or native screen contents. */
internal fun CartAssistDiagnostics.toDiagnosticText(): String = buildString {
    appendLine("다시담기 장바구니 실행 진단")
    appendLine("현재 단계: ${stage.displayName()} (${stage.name})")
    lastActiveStage?.let { appendLine("마지막 확인 단계: ${it.displayName()} (${it.name})") }
    appendLine("중단 원인: ${stopReason.displayName()}")
    appendLine("모드: ${if (verifyOnly) "담기 전 점검" else "1개 추가"}")
    appendLine("경과: ${elapsedMillis / 1_000}초 · 스크롤: ${scrollCount}회")
    appendLine("상품명: ${titleMatched.checked()} · 옵션: ${optionMatched.checked()} · 담기 버튼: ${buttonMatched.checked()}")
    appendLine("담기 클릭: ${when {
        !hasClickRecord -> "과거 기록에 클릭 정보 없음"
        !clickClaimed -> "요청하지 않음"
        clickAccepted == true -> "Android가 클릭 요청을 수락함"
        clickAccepted == false -> "Android가 클릭 요청을 거부함"
        else -> "중복 방지 선점됨 · 실제 클릭 결과 미확인"
    }}")
    detailStructure?.let {
        appendLine("하단 버튼 영역: ${it.bottomContainer.checked()}")
        appendLine("버튼 묶음: ${it.buttonContainer.checked()}")
        appendLine("상세 내용 영역: ${it.detailContent.checked()}")
        appendLine("담기 문구: ${it.cartLabel.checked()} · 바로구매 문구: ${it.buyLabel.checked()}")
    }
    append("읽은 노드: $scannedNodeCount · ID 있는 노드: $resourceIdNodeCount · 검사 한도: ${if (scanTruncated) "도달" else "미도달"}")
}

private fun Boolean.checked() = if (this) "확인" else "미확인"

private fun CartAssistStopReason.displayName(): String = when (this) {
    CartAssistStopReason.NONE -> "없음"
    CartAssistStopReason.USER_CANCEL -> "앱에서 중지 요청"
    CartAssistStopReason.ACCESSIBILITY_INTERRUPTED -> "Android가 접근성 서비스를 중단함"
    CartAssistStopReason.SERVICE_DISCONNECTED -> "접근성 서비스 연결이 끊김"
    CartAssistStopReason.TIMEOUT -> "45초 제한 시간 초과"
    CartAssistStopReason.OTHER -> "그 밖의 실행 오류"
}

private fun CartAssistStage.displayName(): String = when (this) {
    CartAssistStage.IDLE -> "실행 기록 없음"
    CartAssistStage.OPENING_PRODUCT -> "상품 열기"
    CartAssistStage.WAITING_NATIVE_APP -> "쿠팡 앱 화면 대기"
    CartAssistStage.NO_ACTIVE_WINDOW -> "접근성 화면 없음"
    CartAssistStage.OUTSIDE_COUPANG -> "쿠팡 화면을 벗어남"
    CartAssistStage.WAITING_DETAIL_SCREEN -> "상품 상세 구조 확인 대기"
    CartAssistStage.WAITING_TITLE -> "상품명 일치 대기"
    CartAssistStage.WAITING_OPTION -> "옵션 일치 대기"
    CartAssistStage.WAITING_CART_BUTTON -> "담기 버튼 구조 확인 대기"
    CartAssistStage.IDENTITY_STABILIZING -> "상품·옵션 일치 유지 확인"
    CartAssistStage.SCROLLING_DETAIL -> "상품 상세 스크롤"
    CartAssistStage.VERIFIED -> "담기 전 점검 완료"
    CartAssistStage.CLICK_CLAIMED -> "담기 요청 선점"
    CartAssistStage.WAITING_CONFIRMATION -> "쿠팡의 담기 완료 안내 대기"
    CartAssistStage.CONFIRMED -> "담기 완료 확인"
    CartAssistStage.CANCELLED -> "중지됨"
    CartAssistStage.TIMED_OUT -> "시간 초과"
    CartAssistStage.FAILED -> "실패"
}
