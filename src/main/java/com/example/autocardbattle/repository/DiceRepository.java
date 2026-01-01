package com.example.autocardbattle.repository;

import com.example.autocardbattle.entity.DiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface DiceRepository extends JpaRepository<DiceEntity, Long> {
    // 주사위 타입(FIRE, SNIPER 등)으로 특정 주사위 정보를 찾는 기능 추가
    Optional<DiceEntity> findByDiceType(String diceType);
}
