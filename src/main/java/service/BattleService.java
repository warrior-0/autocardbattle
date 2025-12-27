package com.example.autocardbattle.service;

import com.example.autocardbattle.model.Skill;
import com.example.autocardbattle.model.Monster;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class BattleService {

    // 유저별 상태 관리 (멀티 유저 지원)
    private Map<String, List<Skill>> userSkills = new HashMap<>();

    public String simulateBattle(String userId, int duration) {
        StringBuilder log = new StringBuilder();

        // 유저 스킬 초기화
        List<Skill> skills = userSkills.computeIfAbsent(userId, k -> new ArrayList<>(List.of(
                new Skill("강화", 10, 0),
                new Skill("출혈", 4, 2),
                new Skill("약공", 1, 4),
                new Skill("폭발", 6, 1),
                new Skill("회피", 3, 3)
        )));

        Monster monster = new Monster("슬라임", 50, 5);

        for (int time = 1; time <= duration; time++) {
            // READY 후보 수집
            PriorityQueue<Skill> readyQueue = new PriorityQueue<>(
                    Comparator.comparingInt((Skill s) -> s.isReady(time) ? s.getChainOrder() : Integer.MAX_VALUE)
            );

            for (Skill s : skills) if (s.isReady(time)) readyQueue.add(s);

            log.append("t=").append(time).append(" READY: ");
            if (readyQueue.isEmpty()) log.append("-\n");
            else {
                for (Skill s : readyQueue) log.append(s.getName()).append(" ");
                log.append("\n");
            }

            if (!readyQueue.isEmpty()) {
                Skill selected = readyQueue.poll();
                selected.use(time);
                // 스킬 데미지 (단순화)
                int dmg = 5 + selected.getChainOrder();
                monster.takeDamage(dmg);
                log.append("  ▶ USE: ").append(selected.getName()).append(", Monster HP=").append(monster.getHp()).append("\n");
            }

            if (monster.isDead()) {
                log.append("Monster defeated at t=").append(time).append("\n");
                break;
            }
        }

        return log.toString();
    }
}
