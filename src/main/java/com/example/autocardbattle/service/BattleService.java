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

        // 1. 체인 무제한 데이터를 가져옴 (Order순 정렬)
        List<SkillChainEntity> chain = chainRepository.findByFirebaseUidOrderByChainOrderAsc(uid);
        
        // 2. 전투용 Skill 객체로 변환
        List<Skill> skills = chain.stream()
                .map(e -> new Skill(e.getSkillName(), e.getCooldown(), e.getChainOrder(), e.getEffectType(), e.getEffectValue()))
                .collect(Collectors.toList());

        //3. 각 스킬마다 쿨타임 적용
        skills.forEach(s -> s.setNextAvailableTime(s.getCooldown()));

        List<String> logs = new ArrayList<>();
        int playerHp = user.getMaxHp();
        int monsterHp = monster.getHp();
        int time = 0;
        double damageMultiplier = 1.0;

        logs.add("⚔️ 전투 시작! 상대: " + monster.getName() + " (HP: " + monsterHp + ")");

        // 3. 전투 루프 (체인이 길어질 것을 대비해 500틱으로 연장)
        while (time < 500 && playerHp > 0 && monsterHp > 0) {
            time++;
            final int currentTime = time;
            
            // [유저 턴] READY 우선순위 큐 로직
            Skill skillToUse = skills.stream()
                    .filter(s -> s.isReady(currentTime))
                    .sorted(Comparator.comparingInt(Skill::getReadySince).thenComparingInt(Skill::getOrder))
                    .findFirst().orElse(null);

            if (skillToUse != null) {
                switch (skillToUse.getType()) {
                    case "DAMAGE":
                        int dmg = (int) (skillToUse.getValue() * damageMultiplier);
                        monsterHp -= dmg;
                        logs.add(String.format("[T%d] %s! 💥%d 데미지 (적 HP: %d)", currentTime, skillToUse.getName(), dmg, Math.max(0, monsterHp)));
                        damageMultiplier = 1.0; // 버프 소모
                        break;
                    case "HEAL":
                        int heal = skillToUse.getValue();
                        playerHp = Math.min(user.getMaxHp(), playerHp + heal);
                        logs.add(String.format("[T%d] %s! 💚%d 회복 (내 HP: %d)", currentTime, skillToUse.getName(), heal, playerHp));
                        break;
                    case "BUFF":
                        // 예: 가치가 50이면 1.5배 데미지
                        damageMultiplier += (skillToUse.getValue() / 100.0);
                        logs.add(String.format("[T%d] %s! ✨다음 공격 강화 (x%.1f)", currentTime, skillToUse.getName(), damageMultiplier));
                        break;
                }
                skillToUse.use(currentTime);
            }

            // [몬스터 턴] 3틱마다 공격 (너무 자주 때리지 않도록 조정)
            if (time % 3 == 0 && monsterHp > 0) {
                int monsterDmg = monster.getDamage();
                playerHp -= monsterDmg;
                logs.add(String.format("[T%d] 👾몬스터 공격! %d 데미지 (내 HP: %d)", currentTime, monsterDmg, Math.max(0, playerHp)));
            }

            // 승리 판정
            if (monsterHp <= 0) {
                logs.add("🏆 승리했습니다!");
                user.addExp(monster.getExpReward());
                user.setGold(user.getGold() + monster.getGoldReward());
                userRepository.save(user); // 결과 반영
                return new BattleResult("WIN", logs);
            }
        }
        
        // 결과 판정
        String finalResult = (playerHp <= 0) ? "LOSE" : "DRAW";
        logs.add(finalResult.equals("LOSE") ? "💀 패배했습니다..." : "⏱ 시간 초과로 무승부");
        
        return new BattleResult(finalResult, logs);
    }
}
