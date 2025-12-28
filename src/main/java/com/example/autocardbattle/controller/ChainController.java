package com.example.autocardbattle.controller;

import com.example.autocardbattle.entity.SkillChainEntity;
import com.example.autocardbattle.entity.SkillMasterEntity;
import com.example.autocardbattle.repository.InventoryRepository;
import com.example.autocardbattle.repository.SkillChainRepository;
import com.example.autocardbattle.repository.SkillMasterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chain")
public class ChainController {
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private SkillChainRepository chainRepository;
    @Autowired private SkillMasterRepository skillMasterRepository;

    @GetMapping("/list")
    public List<SkillChainEntity> getChain(@RequestParam String uid) {
        return chainRepository.findByFirebaseUidOrderByChainOrderAsc(uid);
    }

    @PostMapping("/equip")
    @Transactional
    public String equipSkill(@RequestBody Map<String, String> body) {
        String uid = body.get("uid");
        String skillName = body.get("skillName");

        // 1. 인벤토리 보유 여부 확인
        inventoryRepository.findByFirebaseUidAndSkillName(uid, skillName)
                .orElseThrow(() -> new RuntimeException("스킬을 보유하고 있지 않습니다."));

        // 2. DB에서 스킬 정보 조회
        SkillMasterEntity master = skillMasterRepository.findById(skillName)
                .orElseThrow(() -> new RuntimeException("존재하지 않는 스킬 데이터입니다."));

        // 3. 현재 체인 길이 확인 (무제한이므로 순서 번호만 생성)
        int nextOrder = chainRepository.findByFirebaseUidOrderByChainOrderAsc(uid).size();

        // 4. 스킬 체인에 추가
        SkillChainEntity newSkill = new SkillChainEntity();
        newSkill.setFirebaseUid(uid);
        newSkill.setSkillName(master.getSkillName());
        newSkill.setCooldown(master.getCooldown());
        newSkill.setEffectType(master.getEffectType());
        newSkill.setEffectValue(master.getEffectValue());
        newSkill.setChainOrder(nextOrder);

        chainRepository.save(newSkill);
        return "OK";
    }

    @PostMapping("/reset")
    @Transactional
    public String resetChain(@RequestParam String uid) {
        chainRepository.deleteByFirebaseUid(uid);
        return "RESET_OK";
    }
}
