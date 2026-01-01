package com.example.autocardbattle.service;

import com.example.autocardbattle.entity.DiceEntity;
import com.example.autocardbattle.repository.DiceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component // 스프링이 자동으로 찾아서 실행하게 해줍니다.
public class DataInitializer implements CommandLineRunner {

    @Autowired 
    private DiceRepository diceRepository;

    @Override
    public void run(String... args) throws Exception {
        // 주사위 데이터가 하나도 없을 때만 실행 (중복 생성 방지)
        if (diceRepository.count() == 0) {
            saveDice("FIRE", "불 주사위", 40, 2, 0.5, "대상 주변 1칸 광역 피해", "#e74c3c");
            saveDice("WIND", "바람 주사위", 15, 3, 2.0, "매우 빠른 공격 속도", "#3498db");
            saveDice("SWORD", "검 주사위", 80, 1, 0.8, "강력한 근접 일격", "#2c3e50");
            saveDice("ELECTRIC", "전기 주사위", 30, 2, 1.0, "주변 적 1명 전이 피해", "#f1c40f");
            saveDice("SNIPER", "저격 주사위", 25, 4, 0.7, "거리당 데미지 대폭 증가", "#27ae60");
            
            System.out.println("✅ 공통 주사위 5종 데이터 생성이 완료되었습니다.");
        }
    }

    private void saveDice(String type, String name, int dmg, int rng, double aps, String desc, String color) {
        DiceEntity d = new DiceEntity();
        d.setDiceType(type);
        d.setName(name);
        d.setDamage(dmg);
        d.setRange(rng);
        d.setAps(aps);
        d.setDesc(desc);
        d.setColor(color);
        diceRepository.save(d);
    }
}
