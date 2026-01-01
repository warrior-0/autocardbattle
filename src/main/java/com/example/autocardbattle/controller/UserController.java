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
@CrossOrigin(origins = "https://warrior-0.github.io") // CORS 설정 추가
public class UserController {

    @Autowired 
    private UserService userService;

    @Autowired 
    private UserRepository userRepository; // saveDeck에서 사용하기 위해 반드시 필요합니다

    @PostMapping("/login")
    public UserEntity login(@RequestBody Map<String, String> data) {
        return userService.loginOrSignup(data.get("uid"), data.get("username"));
    }

    // 메서드가 클래스 내부(괄호 안)에 위치해야 합니다
    @PostMapping("/deck/save")
    public ResponseEntity<?> saveDeck(@RequestBody Map<String, String> data) {
        String uid = data.get("uid");
        String deck = data.get("deck"); // "FIRE,WIND..."
        
        return userRepository.findById(uid).map(user -> {
            user.setSelectedDeck(deck); // UserEntity에 이 필드가 있어야 합니다
            userRepository.save(user);
            return ResponseEntity.ok("덱 저장 완료");
        }).orElse(ResponseEntity.badRequest().body("유저를 찾을 수 없음"));
    }
}
