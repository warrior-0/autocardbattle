package com.example.autocardbattle.service;

import com.example.autocardbattle.entity.SkillChainEntity;
import com.example.autocardbattle.entity.UserEntity;
import com.example.autocardbattle.factory.MonsterFactory;
import com.example.autocardbattle.model.BattleResult;
import com.example.autocardbattle.model.Monster;
import com.example.autocardbattle.model.Skill;
import com.example.autocardbattle.repository.SkillChainRepository;
import com.example.autocardbattle.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class BattleService {
    @Autowired private UserRepository userRepository;
    @Autowired private SkillChainRepository chainRepository;

    @Transactional
    public BattleResult startBattle(String uid, String monsterType) {
        UserEntity user = userRepository.findById(uid).orElseThrow();
        Monster monster = MonsterFactory.createMonster(monsterType);

        List<SkillChainEntity> chain = chainRepository.findByFirebaseUidOrderByChainOrderAsc(uid);
        // DB 엔티티를 전투 로직용 객체(Skill)로 변환
        List<Skill> skills = chain.stream()
                .map(e -> new Skill(e.getSkillName(), e.getCooldown(), e.getChainOrder(), e.getEffectType(), e.getEffectValue()))
                .collect(Collectors.toList());

        List<String> logs = new ArrayList<>();
        int playerHp = user.getMaxHp();
        int monsterHp = monster.getHp();
        int time = 0;
        double damageMultiplier = 1.0;

        logs.add("전투 시작! 상대: " + monster.getName());

        while (time < 100 && playerHp > 0 && monsterHp > 0) {
            time++;
            final int currentTime = time;
            
            // 1. 유저 행동 (우선순위 큐 로직)
            Skill skillToUse = skills.stream()
                    .filter(s -> s.isReady(currentTime))
                    .sorted(Comparator.comparingInt(Skill::getReadySince).thenComparingInt(Skill::getOrder))
                    .findFirst().orElse(null);

            if (skillToUse != null) {
                switch (skillToUse.getType()) {
                    case "DAMAGE":
                        int dmg = (int) (skillToUse.getValue() * damageMultiplier);
                        monsterHp -= dmg;
                        logs.add(String.format("[T%d] %s 발동! %d 데미지 (남은 적 HP: %d)", currentTime, skillToUse.getName(), dmg, Math.max(0, monsterHp)));
                        damageMultiplier = 1.0; // 버프 소모
                        break;
                    case "HEAL":
                        int heal = skillToUse.getValue();
                        playerHp = Math.min(user.getMaxHp(), playerHp + heal);
                        logs.add(String.format("[T%d] %s 발동! %d 회복 (현재 HP: %d)", currentTime, skillToUse.getName(), heal, playerHp));
                        break;
                    case "BUFF":
                        damageMultiplier += (skillToUse.getValue() / 100.0);
                        logs.add(String.format("[T%d] %s 발동! 다음 공격 강화", currentTime, skillToUse.getName()));
                        break;
                }
                skillToUse.use(currentTime);
            }

            // 2. 몬스터 반격 (매 3초마다 공격한다고 가정)
            if (time % 3 == 0 && monsterHp > 0) {
                playerHp -= monster.getDamage();
                // logs.add(String.format("[T%d] 몬스터 반격! (내 HP: %d)", currentTime, playerHp));
            }

            if (monsterHp <= 0) {
                logs.add("🎉 승리했습니다!");
                user.addExp(monster.getExpReward());
                user.setGold(user.getGold() + monster.getGoldReward());
                userRepository.save(user);
                return new BattleResult("WIN", logs);
            }
        }
        
        logs.add("패배하거나 무승부...");
        return new BattleResult("LOSE", logs);
    }
}
