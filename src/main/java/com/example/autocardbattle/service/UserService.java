package com.example.autocardbattle.service;

import com.example.autocardbattle.entity.UserEntity;
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
        // 1. 중복 닉네임 체크
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("이미 존재하는 닉네임입니다.");
        }

        // 2. 유저 생성 및 저장
        UserEntity user = new UserEntity();
        user.setFirebaseUid(uid);
        user.setUsername(username);
        userRepository.save(user);

        return user;
    }

    public UserEntity getUser(String uid) {
        return userRepository.findById(uid)
            .orElseThrow(() -> new RuntimeException("User not found"));
    }
}
