package com.example.autocardbattle.controller;

import com.example.autocardbattle.entity.MapTileEntity;
import com.example.autocardbattle.service.MapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/map")
public class MapController {
    @Autowired private MapService mapService;

    // 1. 새로운 맵을 공용 풀에 업로드 (중복 체크 포함)
    @PostMapping("/save")
    public ResponseEntity<String> upload(@RequestBody List<MapTileEntity> tiles) {
        try {
            // [수정] 메서드명을 MapService에 맞춰 uploadNewMap으로 변경
            mapService.uploadNewMap(tiles);
            return ResponseEntity.ok("성공: 새로운 맵이 공유 풀에 등록되었습니다.");
        } catch (RuntimeException e) {
            // 중복된 맵일 경우 예외 메시지 반환
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("오류가 발생했습니다.");
        }
    }

    // 2. 대전 시작 시 랜덤으로 맵 하나 가져오기
    @GetMapping("/battle-start")
    public List<MapTileEntity> startBattle() {
        // [수정] 존재하지 않는 getMap 대신 getRandomBattleMap 사용
        return mapService.getRandomBattleMap();
    }
}
