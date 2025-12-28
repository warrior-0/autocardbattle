package com.example.autocardbattle.service;

import com.example.autocardbattle.entity.InventoryEntity;
import com.example.autocardbattle.entity.UserEntity;
import com.example.autocardbattle.repository.InventoryRepository;
import com.example.autocardbattle.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    @Autowired private UserRepository userRepository;
    @Autowired private InventoryRepository inventoryRepository;

    @Transactional
    public UserEntity createUser(String uid, String username) {
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("이미 존재하는 닉네임입니다.");
        }
        UserEntity user = new UserEntity();
        user.setFirebaseUid(uid);
        user.setUsername(username);
        userRepository.save(user);

        // 기본 스킬 3개 지급 (약공)
        InventoryEntity basicSkill = new InventoryEntity();
        basicSkill.setFirebaseUid(uid);
        basicSkill.setSkillName("기본 펀치");
        basicSkill.setQuantity(3);
        inventoryRepository.save(basicSkill);
        
        // 힐 스킬 1개 지급
        InventoryEntity healSkill = new InventoryEntity();
        healSkill.setFirebaseUid(uid);
        healSkill.setSkillName("응급 처치");
        healSkill.setQuantity(1);
        inventoryRepository.save(healSkill);

        return user;
    }

    public UserEntity getUser(String uid) {
        return userRepository.findById(uid)
            .orElseThrow(() -> new RuntimeException("User not found"));
    }
}
