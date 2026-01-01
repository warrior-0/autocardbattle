package com.example.autocardbattle.controller;

import com.example.autocardbattle.entity.MapTileEntity;
import com.example.autocardbattle.repository.MapRepository;
import com.example.autocardbattle.service.MapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/map")
@CrossOrigin(origins = "https://warrior-0.github.io")
public class MapController {

    @Autowired
    private MapRepository mapRepository;

    @Autowired
    private MapService mapService;

    @PostMapping("/save")
    public ResponseEntity<?> saveMap(@RequestBody Map<String, String> request) {
        try {
            mapService.saveMap(request.get("creatorUid"), request.get("mapData"));
            return ResponseEntity.ok("전장이 성공적으로 저장되었습니다.");
        } catch (RuntimeException e) {
            // "이미 동일한 구조의 전장이 존재합니다!" 메시지를 프론트로 전달
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("서버 오류가 발생했습니다.");
        }
    }
}
