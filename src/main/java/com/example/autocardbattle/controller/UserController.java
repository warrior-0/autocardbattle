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

    @PostMapping("/login")
    public UserEntity login(@RequestBody Map<String, String> data) {
        return userService.loginOrSignup(data.get("uid"), data.get("username"));
    }
}
