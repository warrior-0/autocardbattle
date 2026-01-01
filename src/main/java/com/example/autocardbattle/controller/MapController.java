package com.example.autocardbattle.controller;

import com.example.autocardbattle.entity.MapTileEntity;
import com.example.autocardbattle.service.MapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/map")
public class MapController {
    @Autowired private MapService mapService;

    // 프론트엔드에서 4x8 리스트를 보내면 대칭으로 저장
    @PostMapping("/save")
    public String save(@RequestParam String uid, @RequestBody List<MapTileEntity> tiles) {
        try {
            mapService.saveSymmetricMap(uid, tiles);
            return "성공: 8x8 대칭 맵이 생성되었습니다.";
        } catch (Exception e) {
            return "실패: " + e.getMessage();
        }
    }

    // 저장된 전체 맵 불러오기
    @GetMapping("/load")
    public List<MapTileEntity> load(@RequestParam String uid) {
        return mapService.getMap(uid);
    }
}
