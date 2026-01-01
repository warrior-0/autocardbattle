package com.example.autocardbattle.controller;

import com.example.autocardbattle.entity.UserEntity;
import com.example.autocardbattle.repository.UserRepository;
import com.example.autocardbattle.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {
    @Autowired private UserService userService;

    @PostMapping("/login")
    public UserEntity login(@RequestBody Map<String, String> data) {
        return userService.loginOrSignup(data.get("uid"), data.get("username"));
    }
}

@PostMapping("/deck/save")
public ResponseEntity<?> saveDeck(@RequestBody Map<String, String> data) {
    String uid = data.get("uid");
    String deck = data.get("deck"); // "FIRE,WIND..."
    
    return userRepository.findById(uid).map(user -> {
        user.setSelectedDeck(deck);
        userRepository.save(user);
        return ResponseEntity.ok("덱 저장 완료");
    }).orElse(ResponseEntity.badRequest().body("유저를 찾을 수 없음"));
}
