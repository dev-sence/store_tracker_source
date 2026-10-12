package sence.playf.storetracker.alba;

/** 서버 `/api/alba/status`가 돌려주는 "지금 일하는 중" 고용 1건을 그대로 담는다. */
public record AlbaEntry(String employer, String employee, String job, String zone, long hiredAtMillis) {
}
