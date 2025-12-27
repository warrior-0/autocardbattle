package service;

import model.Skill;
import model.Monster;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class BattleService {

    // 배틀 실행
    public List<String> executeBattle(String userId, int duration, List<Skill> skills, Monster monster) {
        List<String> battleLog = new ArrayList<>();

        // READY 큐를 우선순위 큐로 관리
        PriorityQueue<Skill> readyQueue = new PriorityQueue<>(
                (a, b) -> {
                    if (a.getReadySinceTime() != b.getReadySinceTime()) {
                        return Integer.compare(a.getReadySinceTime(), b.getReadySinceTime());
                    }
                    return Integer.compare(a.getChainOrder(), b.getChainOrder());
                }
        );

        for (int time = 1; time <= duration; time++) {

            // 1️⃣ READY 후보 수집
            readyQueue.clear();
            for (Skill s : skills) {
                if (s.isReady(time)) {
                    readyQueue.add(s);
                }
            }

            // 로그: READY BEFORE
            battleLog.add("t=" + time + " READY BEFORE: " + (readyQueue.isEmpty() ? "-" :
                    String.join(" ", readyQueue.stream().map(Skill::getName).toList())));

            if (!readyQueue.isEmpty()) {
                // 2️⃣ 사용할 스킬 선택 (우선순위 큐 기준)
                Skill selected = readyQueue.poll();
                selected.use(time);

                battleLog.add("  ▶ USE: " + selected.getName());
            }

            // 3️⃣ READY AFTER
            List<String> readyAfterList = new ArrayList<>();
            for (Skill s : skills) {
                if (s.isReady(time)) {
                    readyAfterList.add(s.getName());
                }
            }
            battleLog.add("  READY AFTER: " + (readyAfterList.isEmpty() ? "-" :
                    String.join(" ", readyAfterList)));
        }

        return battleLog;
    }
}
