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
    // 유저별 할당된 방 ID 및 해당 방의 맵 정보를 저장 (동시성 지원을 위해 ConcurrentHashMap 권장)
    private static final Map<String, String> userRooms = new ConcurrentHashMap<>();
    private static final Map<String, List<MapTileEntity>> roomMaps = new ConcurrentHashMap<>();

    @PostMapping("/cancel")
    public ResponseEntity<?> cancelMatch(@RequestParam String userUid) {
        synchronized (matchingQueue) {
            matchingQueue.remove(userUid); // 대기열에서 제거
            userRooms.remove(userUid);     // 할당된 방 정보가 있다면 제거
        }
        return ResponseEntity.ok("매칭 취소 완료");
    }

    //유저 룸 반환하기
    public static Map<String, String> getUserRooms() {
        return userRooms;
    }
    
    //게임이 종료된 후 방 데이터 삭제
    public static void removeRoomData(String roomId) {
        if (roomId != null) {
            roomMaps.remove(roomId); // 해당 방의 맵 데이터 삭제
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
                
                // 고유한 방 ID 생성
                String roomId = "room_" + UUID.randomUUID().toString().substring(0, 8);
                
                // 두 유저에게 같은 방 ID 부여
                userRooms.put(p1, roomId);
                userRooms.put(p2, roomId);
                
                // 이 방에서 사용할 공통 랜덤 맵 미리 결정
                List<MapTileEntity> selectedMap = mapRepository.findRandomMap();
                roomMaps.put(roomId, selectedMap);

                return ResponseEntity.ok(Map.of("roomId", roomId));
            }
        }
        // 아직 대기 중 (202 Accepted)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body("매칭 대기 중...");
    }

    // 1. 전투 시작: 매칭된 방의 공통 맵과 유저별 첫 손패 지급
    @PostMapping("/start")
    public BattleResponse startBattle(@RequestParam String userUid, @RequestBody List<String> userDeck) {
        String roomId = userRooms.get(userUid);
        
        // 매칭된 방의 공통 맵 데이터를 가져옴 (없으면 새로 하나 뽑음)
        List<MapTileEntity> randomMap = (roomId != null && roomMaps.containsKey(roomId)) 
                                        ? roomMaps.get(roomId) 
                                        : mapRepository.findRandomMap();
        
        // 덱 셔플 후 첫 손패 2개 선택
        List<String> mutableDeck = new ArrayList<>(userDeck);
        Collections.shuffle(mutableDeck);
        List<String> hand = mutableDeck.subList(0, 2);

        return new BattleResponse(randomMap, hand, 1);
    }

    // 2. 배치 정보 수신 및 다음 턴 주사위 지급 (REST 방식 유지 시)
    @PostMapping("/place")
    public Map<String, Object> placeDice(@RequestBody PlacementRequest request) {
        Map<String, Object> response = new HashMap<>();
        
        if (request.getTurn() < 3) {
            List<String> mutableDeck = new ArrayList<>(request.getUserDeck());
            Collections.shuffle(mutableDeck);
            
            response.put("hand", mutableDeck.subList(0, 2));
            response.put("turn", request.getTurn() + 1);
            response.put("status", "CONTINUE");
        } else {
            response.put("status", "REVEAL");
        }
        return response;
    }
}
