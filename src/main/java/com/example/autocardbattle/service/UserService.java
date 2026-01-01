package com.example.autocardbattle.service;

import com.example.autocardbattle.entity.UserEntity;
import com.example.autocardbattle.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    @Autowired private UserRepository userRepository;

    @Transactional
    public UserEntity loginOrSignup(String uid, String username) {
        // 1. 이미 존재하는 유저인지 확인
        return userRepository.findById(uid).orElseGet(() -> {
            // 2. 신규 유저라면 생성 및 저장
            UserEntity newUser = new UserEntity();
            newUser.setFirebaseUid(uid);
            newUser.setUsername(username);
            return userRepository.save(newUser);
        });
    }
}
