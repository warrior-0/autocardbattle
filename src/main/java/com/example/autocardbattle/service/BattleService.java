package com.example.autocardbattle.service;

import com.example.autocardbattle.model.Skill;
import com.example.autocardbattle.model.Monster;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class BattleService {

    // 유저마다 독립적인 스킬/몬스터 객체 생성 가능
    public String simulateBattle(String userId, int duration) {
        StringBuilder log = new StringBuilder();
        int time = 0;

        List<Skill> skills = new ArrayList<>();
        skills.add(new Skill("강화", 10, 0));
        skills.add(new Skill("출혈", 4, 1));
        skills.add(new Skill("약공", 1, 2));
        skills.add(new Skill("폭발", 6, 3));
        skills.add(new Skill("회피", 3, 4));

        List<Monster> monsters = new ArrayList<>();
        monsters.add(new Monster("슬라임", 50, List.of(skills.get(1), skills.get(2))));
        monsters.add(new Monster("고블린", 80, List.of(skills.get(3), skills.get(4))));

        while (time < duration) {
            List<Skill> readySkills = new ArrayList<>();
            for (Skill s : skills) {
                if (s.isReady(time)) readySkills.add(s);
            }

            // READY 시간 우선, 체인 순서 보조
            readySkills.sort(Comparator.comparing(Skill::getReadySinceTime)
                    .thenComparing(Skill::getChainOrder));

            if (!readySkills.isEmpty()) {
                Skill selected = readySkills.get(0);
                selected.use(time);
                log.append(String.format("[t=%.1f] %s 발동!\n", time, selected.getName()));

                // 몬스터 데미지 적용 (단순 예시)
                for (Monster m : monsters) {
                    if (m.isAlive()) {
                        m.takeDamage(5);
                        log.append(String.format("  → %s 데미지 5, 남은 HP: %d\n", m.getName(), m.getHp()));
                        break;
                    }
                }
            }

            time += 1; // 1초 단위
        }

        return log.toString();
    }
}
