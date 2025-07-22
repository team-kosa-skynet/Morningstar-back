package com.gaebang.backend.global.util;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class NicknameGenerator {

    // 1. 목록을 미리 static final로 만들어 다시 생성되지 않도록 함
    private static final List<String> PART1 = List.of(
            "자유로운", "행복한", "당당한", "수줍은", "용기있는", "엉뚱한", "신나는",
            "장난꾸러기", "호기심많은", "커피마시는", "코딩하는", "산책하는", "노래하는", "상상하는",
            "게으른", "부지런한", "슬기로운", "사랑스러운", "단순한", "긍정적인", "평화로운",
            "솔직한", "의리있는", "매력적인", "배고픈", "피곤한", "여행하는", "꿈꾸는", "웃기는"
    );

    private static final List<String> PART2 = List.of(
            "숲속의", "바다의", "도시의", "우주속의", "구름위의", "도서관의", "남극의", "사막의",
            "하얀", "까만", "노란", "파란", "빨간", "초록", "보라색", "무지개색", "황금색",
            "새벽의", "아침의", "점심의", "저녁의", "한밤중의", "비밀스러운", "솜털같은", "반짝이는",
            "줄무늬", "물방울무늬", "그림자속", "언덕위의", "동굴속"
    );

    private static final List<String> PART3 = List.of(
            "사자", "호랑이", "코끼리", "강아지", "고양이", "쿼카", "카피바라", "알파카",
            "펭귄", "북극곰", "다람쥐", "고슴도치", "부엉이", "미어캣", "너구리", "수달",
            "돌고래", "거북이", "악어", "카멜레온", "치타", "기린", "판다", "코알라",
            "별", "달", "해"
    );

    // 2. 여러 스레드에서 동시에 호출해도 안전한 ThreadLocalRandom을 사용
    private static String getRandomPart(List<String> list) {
        int randomIndex = ThreadLocalRandom.current().nextInt(list.size());
        return list.get(randomIndex);
    }

    public static String generateName() {
        String part1 = getRandomPart(PART1);
        String part2 = getRandomPart(PART2);
        String part3 = getRandomPart(PART3);

        // 띄어쓰기 없이 조합
        return part1 + part2 + part3;

        // 띄어쓰기를 넣어 가독성을 높이려면 아래 코드를 사용
        // return part1 + " " + part2 + " " + part3;
    }
}