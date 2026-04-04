package com.example.autocardbattle.controller;

import com.example.autocardbattle.entity.UserEntity;
import com.example.autocardbattle.repository.UserRepository;
import com.example.autocardbattle.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@CrossOrigin(origins = "https://warrior-0.github.io") // CORS 설정 추가
public class UserController {

    @Autowired 
    private UserService userService;

    @Autowired 
    private UserRepository userRepository;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> data) {
        try {
            UserEntity user = userService.loginOrSignup(data.get("uid"), data.get("username"));
            return ResponseEntity.ok(user);
        } catch (RuntimeException e) {
            // 중복 닉네임 등 예외 발생 시 400 에러와 메시지 반환
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @PostMapping("/deck/save")
    public ResponseEntity<?> saveDeck(@RequestBody Map<String, String> data) {
        String uid = data.get("uid");
        String deck = data.get("deck");
        
        return userRepository.findById(uid).map(user -> {
            user.setSelectedDeck(deck);
            userRepository.save(user);
            return ResponseEntity.ok("덱 저장 완료");
        }).orElse(ResponseEntity.badRequest().body("유저를 찾을 수 없음"));
    }
}
