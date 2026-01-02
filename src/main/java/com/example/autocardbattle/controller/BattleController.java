package com.example.autocardbattle.controller;

import com.example.autocardbattle.dto.*;
import com.example.autocardbattle.entity.*;
import com.example.autocardbattle.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/battle")
@CrossOrigin(origins = "https://warrior-0.github.io")
public class BattleController {
    
    @Autowired private MapRepository mapRepository;
    @Autowired private DiceRepository diceRepository;
    
    // 대기열을 위한 큐 (유저 UID 저장)
    private static final Queue<String> matchingQueue = new LinkedList<>();
    
    // 유저별 할당된 방 ID 저장
    private static final Map<String, String> userRooms = new ConcurrentHashMap<>();
    
    // 방별 할당된 공통 맵 정보 저장
    private static final Map<String, List<MapTileEntity>> roomMaps = new ConcurrentHashMap<>();

    // ✅ [추가] 방별 유저 준비 상태 관리 (싱크용)
    // 방 ID를 키로 하고, 준비된 유저 UID들의 집합을 값으로 가집니다.
    public static final Map<String, Set<String>> roomReadyStatus = new ConcurrentHashMap<>();

    @PostMapping("/cancel")
    public ResponseEntity<?> cancelMatch(@RequestParam String userUid) {
        synchronized (matchingQueue) {
            matchingQueue.remove(userUid); 
            userRooms.remove(userUid);     
        }
        return ResponseEntity.ok("매칭 취소 완료");
    }

    public static Map<String, String> getUserRooms() {
        return userRooms;
    }
    
    public static void removeRoomData(String roomId) {
        if (roomId != null) {
            roomMaps.remove(roomId);
            roomReadyStatus.remove(roomId); // 준비 상태 데이터도 함께 삭제
            userRooms.values().removeIf(id -> id.equals(roomId));
        }
    }

    @PostMapping("/match")
    public ResponseEntity<?> requestMatch(@RequestParam String userUid) {
        synchronized (matchingQueue) {
            // 1. 이미 매칭되어 방이 할당된 경우 바로 방 ID 반환
            if (userRooms.containsKey(userUid)) {
                return ResponseEntity.ok(Map.of("roomId", userRooms.get(userUid)));
            }

            // 2. 대기열에 추가 (중복 방지)
            if (!matchingQueue.contains(userUid)) {
                matchingQueue.add(userUid);
            }

            // 3. 2명이 모였으면 방 생성 및 맵 결정
            if (matchingQueue.size() >= 2) {
                String p1 = matchingQueue.poll();
                String p2 = matchingQueue.poll();
                
                String roomId = "room_" + UUID.randomUUID().toString().substring(0, 8);
                
                userRooms.put(p1, roomId);
                userRooms.put(p2, roomId);
                
                // 이 방에서 사용할 공통 랜덤 맵 미리 결정
                List<MapTileEntity> selectedMap = mapRepository.findRandomMap();
                roomMaps.put(roomId, selectedMap);

                return ResponseEntity.ok(Map.of("roomId", roomId));
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body("매칭 대기 중...");
    }

    // ✅ [수정] startBattle: 이제 맵 데이터만 주는 역할로 축소됩니다. (핸드는 웹소켓으로 받음)
    @PostMapping("/start")
    public BattleResponse startBattle(@RequestParam String userUid, @RequestBody List<String> userDeck) {
        // 핸드는 빈 리스트로 보냄 (GAME_START 메시지로 따로 받을 것임)
        return new BattleResponse(new ArrayList<>(), new ArrayList<>(), 1);
    }

    // 방별 저장된 맵 정보를 가져오는 헬퍼 메서드 (Service에서 사용)
    public static List<MapTileEntity> getRoomMap(String roomId) {
        return roomMaps.get(roomId);
    }
}
