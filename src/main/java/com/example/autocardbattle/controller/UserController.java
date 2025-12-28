package com.example.autocardbattle.controller;
import com.example.autocardbattle.entity.UserEntity;
import com.example.autocardbattle.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {
    @Autowired private UserService userService;

    @PostMapping("/signup")
    public UserEntity signup(@RequestBody Map<String, String> body) {
        return userService.createUser(body.get("uid"), body.get("username"));
    }

    @GetMapping("/login")
    public UserEntity login(@RequestParam String uid) {
        return userService.getUser(uid);
    }
}
