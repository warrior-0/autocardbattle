package com.example.autocardbattle.service;

import com.example.autocardbattle.dto.BattleMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class RlAiService {

    private static final int GRID_SIZE = 8;
    private static final int TOTAL_TILES = GRID_SIZE * GRID_SIZE;
    private static final int MAX_UNIT_LEVEL = 7;
    private final ObjectMapper objectMapper;

    @Value("${autocardbattle.ai.model.path:src/main/resources/python/q_policy.json}")
    private String defaultModelPath;
    @Value("${autocardbattle.ai.best-model.path:}")
    private String bestModelPath;

    private volatile PolicyModel activeModel;
    private volatile long activeModelLastModified = -1L;

    public RlAiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        Path path = resolvePreferredModelPath();
        System.out.println("AI Model loading from: " + path.toAbsolutePath());
        try {
            if (path.toFile().exists()) {
                activeModel = loadModel(path);
                activeModelLastModified = getLastModified(path);
                System.out.println("AI Model loaded successfully from " + path);
            } else {
                System.err.println("AI Model file not found at " + path + ". AI will use random actions until a model is activated.");
                activeModel = PolicyModel.empty(path);
                activeModelLastModified = -1L;
            }
        } catch (Exception e) {
            System.err.println("Failed to load AI model: " + e.getMessage());
            activeModel = PolicyModel.empty(path);
            activeModelLastModified = -1L;
        }
    }

    public synchronized void activateModel(Path modelPath) throws IOException {
        activeModel = loadModel(modelPath);
        activeModelLastModified = getLastModified(modelPath);
    }

    public Map<String, Object> getActiveModelInfo() {
        PolicyModel model = activeModel;
        Map<String, Object> map = new HashMap<>();
        map.put("path", model.path.toString());
        map.put("stateSize", model.stateSize);
        map.put("actionSize", model.actionSize);
        map.put("mapDataSize", model.mapData.size());
        map.put("playerTiles", model.playerTiles.size());
        map.put("enemyTiles", model.enemyTiles.size());
        return map;
    }

    public Optional<BattleMessage> chooseAction(
            BattleService.GameState state,
            String roomId,
            String mapData,
            List<String> hand,
            List<BattleMessage> aiPlacements,
            List<BattleMessage> humanPlacements,
            List<int[]> availableTiles,
            int aiActionsUsed,
            int aiHp,
            int humanHp,
            List<String> diceTypes
    ) {
        refreshActiveModelIfNeeded();

        if (hand == null || hand.isEmpty()) {
            return Optional.empty();
        }
        if (activeModel == null) {
            return Optional.empty();
        }

        List<Integer> canonicalTiles = resolveCanonicalTiles(availableTiles);
        if (canonicalTiles.isEmpty()) {
            return Optional.empty();
        }

        int[] stateVector = encodeState(
                state,
                hand,
                aiPlacements,
                humanPlacements,
                aiActionsUsed,
                aiHp,
                humanHp,
                diceTypes,
                mapData
        );

        double[] qValues = activeModel.predict(stateVector);

        Set<Integer> availableTileSet = new HashSet<>();
        for (int[] tile : availableTiles) {
            if (tile == null || tile.length < 2) {
                continue;
            }
            availableTileSet.add(toTileIndex(tile[0], tile[1]));
        }

        Map<Integer, BattleMessage> aiPlacementByTile = new HashMap<>();
        for (BattleMessage placement : aiPlacements) {
            aiPlacementByTile.put(toTileIndex(placement.getX(), placement.getY()), placement);
        }

        List<CandidateAction> candidates = enumerateCandidates(
                hand,
                canonicalTiles,
                availableTileSet,
                aiPlacementByTile
        );

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // Hybrid(CNN) 체크포인트는 best_model의 policy_head.bias를 기준으로
        // 유효 후보 action index의 확률 분포를 계산해 선택합니다.
        // (추론 불가 시에만 랜덤 fallback)
        if (activeModel.hybridModel) {
            CandidateAction selected = selectHybridCandidateFromBestModel(candidates, activeModel);
            if (selected == null) {
                selected = selectFallbackCandidate(candidates);
            }
            if (selected == null || selected.isCompleteTurn()) {
                return Optional.empty();
            }
            return Optional.of(toBattleAction(selected, state));
        }

        if (qValues.length == 0) {
            CandidateAction selected = selectFallbackCandidate(candidates);
            if (selected == null || selected.isCompleteTurn()) {
                return Optional.empty();
            }
            return Optional.of(toBattleAction(selected, state));
        }

        // 1. 유효한 행동 후보들의 확률 분포 계산 (Softmax)
        // PPO 모델의 출력(Logits)을 확률 분포로 변환하여 확률적 선택을 수행합니다.
        List<CandidateAction> validCandidates = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        double maxScore = Double.NEGATIVE_INFINITY;

        for (CandidateAction candidate : candidates) {
            if (candidate.actionIndex < 0 || candidate.actionIndex >= qValues.length) continue;
            
            double score = qValues[candidate.actionIndex];
            validCandidates.add(candidate);
            scores.add(score);
            if (score > maxScore) maxScore = score;
        }

        if (validCandidates.isEmpty()) {
            return Optional.empty();
        }

        // Softmax: PPO 모델이 학습한 확률 분포(Logits)를 그대로 확률값으로 변환합니다.
        // 엔트로피 정규화(Entropy Regularization)를 통해 학습된 탐색 능력을 100% 활용합니다.
        double sumExp = 0.0;
        double[] probs = new double[scores.size()];
        for (int i = 0; i < scores.size(); i++) {
            probs[i] = Math.exp(scores.get(i) - maxScore);
            sumExp += probs[i];
        }

        for (int i = 0; i < probs.length; i++) {
            probs[i] /= (sumExp + 1e-8);
        }

        // 2. 확률 분포에 따른 샘플링 (PPO 기반 확률적 선택)
        // 가장 높은 점수의 행동만 선택하는 것이 아니라, 확률 분포에 따라 다양한 행동을 선택합니다.
        double r = ThreadLocalRandom.current().nextDouble();
        double cumulative = 0.0;
        CandidateAction selected = validCandidates.get(validCandidates.size() - 1); // fallback

        for (int i = 0; i < probs.length; i++) {
            cumulative += probs[i];
            if (r <= cumulative) {
                selected = validCandidates.get(i);
                break;
            }
        }

        // 3. 선택된 행동이 패스(PASS)인 경우
        if (selected.isCompleteTurn()) {
            return Optional.empty();
        }

        return Optional.of(toBattleAction(selected, state));
    }

    private CandidateAction selectFallbackCandidate(List<CandidateAction> candidates) {
        List<CandidateAction> nonPass = new ArrayList<>();
        for (CandidateAction c : candidates) {
            if (!c.isCompleteTurn()) {
                nonPass.add(c);
            }
        }
        if (nonPass.isEmpty()) {
            return null;
        }
        int pick = ThreadLocalRandom.current().nextInt(nonPass.size());
        return nonPass.get(pick);
    }

    private CandidateAction selectHybridCandidateFromBestModel(List<CandidateAction> candidates, PolicyModel model) {
        if (candidates == null || candidates.isEmpty() || model == null) {
            return null;
        }
        if (model.policyHeadBias == null || model.policyHeadBias.length == 0) {
            return null;
        }

        List<CandidateAction> validCandidates = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        double maxScore = Double.NEGATIVE_INFINITY;

        for (CandidateAction candidate : candidates) {
            int idx = candidate.actionIndex;
            if (idx < 0 || idx >= model.policyHeadBias.length) {
                continue;
            }
            double score = model.policyHeadBias[idx];
            validCandidates.add(candidate);
            scores.add(score);
            if (score > maxScore) {
                maxScore = score;
            }
        }

        if (validCandidates.isEmpty()) {
            return null;
        }

        double sumExp = 0.0;
        double[] probs = new double[scores.size()];
        for (int i = 0; i < scores.size(); i++) {
            probs[i] = Math.exp(scores.get(i) - maxScore);
            sumExp += probs[i];
        }
        if (sumExp <= 0.0) {
            return null;
        }

        for (int i = 0; i < probs.length; i++) {
            probs[i] /= (sumExp + 1e-8);
        }

        double r = ThreadLocalRandom.current().nextDouble();
        double cumulative = 0.0;
        for (int i = 0; i < probs.length; i++) {
            cumulative += probs[i];
            if (r <= cumulative) {
                return validCandidates.get(i);
            }
        }
        return validCandidates.get(validCandidates.size() - 1);
    }

    private BattleMessage toBattleAction(CandidateAction chosen, BattleService.GameState state) {
        BattleMessage action = new BattleMessage();
        action.setSender(state.aiUid);
        action.setTurn(state.turn);
        action.setDiceType(chosen.diceType);
        action.setX(chosen.x);
        action.setY(chosen.y);

        if (chosen.mergeTarget != null) {
            action.setType("MERGE");
            action.setLevel(chosen.mergeTarget.getLevel() + 1);
        } else {
            action.setType("PLACE");
            action.setLevel(1);
        }
        return action;
    }

    private int[] encodeState(
            BattleService.GameState state,
            List<String> hand,
            List<BattleMessage> aiPlacements,
            List<BattleMessage> humanPlacements,
            int aiActionsUsed,
            int aiHp,
            int humanHp,
            List<String> diceTypes,
            String mapDataRaw
    ) {
        List<Integer> vector = new ArrayList<>(TOTAL_TILES * 5 + diceTypes.size() + 5);

        // 1) map layout (64)
        vector.addAll(encodeMap(parseMapData(mapDataRaw)));

        // 2) own board types (64)
        vector.addAll(encodeBoardTypes(aiPlacements, diceTypes));

        // 3) own board levels (64)
        vector.addAll(encodeBoardLevels(aiPlacements));

        // 4) enemy board types (64)
        vector.addAll(encodeBoardTypes(humanPlacements, diceTypes));

        // 5) enemy board levels (64)
        vector.addAll(encodeBoardLevels(humanPlacements));

        // 6) hand counts per dice type
        for (String diceType : diceTypes) {
            int count = 0;
            for (String h : hand) {
                if (diceType.equals(h)) {
                    count++;
                }
            }
            vector.add(count);
        }

        // 7) game metadata
        vector.add(Math.max(0, aiHp));
        vector.add(Math.max(0, humanHp));
        vector.add(Math.max(0, aiActionsUsed));
        vector.add(Math.max(0, state.turn)); // game_simulator.py의 current_round는 1부터 시작함
        vector.add(state.lastRoundResult + 1);

        return vector.stream().mapToInt(Integer::intValue).toArray();
    }

    private List<Integer> encodeMap(List<String> mapData) {
        List<Integer> encoded = new ArrayList<>(TOTAL_TILES);
        for (String kind : mapData) {
            String t = kind == null ? "" : kind.trim().toUpperCase();
            if (t.equals("BOTH_TILE") || t.equals("SHARED_TILE") || t.equals("ANY_TILE")) {
                encoded.add(3);
            } else if (t.equals("MY_TILE") || t.equals("PLAYER_TILE") || t.equals("ALLY_TILE")) {
                encoded.add(1);
            } else if (t.equals("ENEMY_TILE")) {
                encoded.add(2);
            } else {
                encoded.add(0);
            }
        }

        while (encoded.size() < TOTAL_TILES) {
            encoded.add(0);
        }
        if (encoded.size() > TOTAL_TILES) {
            return new ArrayList<>(encoded.subList(0, TOTAL_TILES));
        }
        return encoded;
    }

    private List<Integer> encodeBoardTypes(List<BattleMessage> placements, List<String> diceTypes) {
        int[] board = new int[TOTAL_TILES];
        for (BattleMessage unit : placements) {
            int tile = toTileIndex(unit.getX(), unit.getY());
            if (tile < 0 || tile >= TOTAL_TILES) {
                continue;
            }
            int idx = diceTypes.indexOf(unit.getDiceType());
            board[tile] = idx >= 0 ? idx + 1 : 0;
        }
        List<Integer> list = new ArrayList<>(TOTAL_TILES);
        for (int v : board) {
            list.add(v);
        }
        return list;
    }

    private List<Integer> encodeBoardLevels(List<BattleMessage> placements) {
        int[] board = new int[TOTAL_TILES];
        for (BattleMessage unit : placements) {
            int tile = toTileIndex(unit.getX(), unit.getY());
            if (tile < 0 || tile >= TOTAL_TILES) {
                continue;
            }
            board[tile] = Math.max(0, unit.getLevel());
        }
        List<Integer> list = new ArrayList<>(TOTAL_TILES);
        for (int v : board) {
            list.add(v);
        }
        return list;
    }

    private List<Integer> resolveCanonicalTiles(List<int[]> availableTiles) {
        List<Integer> fallback = new ArrayList<>();
        for (int[] tile : availableTiles) {
            fallback.add(toTileIndex(tile[0], tile[1]));
        }
        fallback.sort(Integer::compareTo);
        return fallback;
    }

    private int overlapCount(Set<Integer> available, List<Integer> tiles) {
        int count = 0;
        for (Integer tile : tiles) {
            if (available.contains(tile)) {
                count++;
            }
        }
        return count;
    }

    private List<CandidateAction> enumerateCandidates(
            List<String> hand,
            List<Integer> canonicalTiles,
            Set<Integer> availableTileSet,
            Map<Integer, BattleMessage> aiPlacementByTile
    ) {
        List<CandidateAction> candidates = new ArrayList<>();
        candidates.add(CandidateAction.pass());

        for (int handIndex = 0; handIndex < hand.size(); handIndex++) {
            String diceType = hand.get(handIndex);

            for (int tilePos = 0; tilePos < canonicalTiles.size(); tilePos++) {
                int tileIndex = canonicalTiles.get(tilePos);
                int x = tileIndex % GRID_SIZE;
                int y = tileIndex / GRID_SIZE;

                BattleMessage existing = aiPlacementByTile.get(tileIndex);
                if (existing != null) {
                    if (diceType.equals(existing.getDiceType()) && existing.getLevel() < MAX_UNIT_LEVEL) {
                        candidates.add(new CandidateAction(
                                false,
                                1 + (handIndex * canonicalTiles.size()) + tilePos,
                                diceType,
                                x,
                                y,
                                existing
                        ));
                    }
                    continue;
                }

                if (availableTileSet.contains(tileIndex)) {
                    candidates.add(new CandidateAction(
                            false,
                            1 + (handIndex * canonicalTiles.size()) + tilePos,
                            diceType,
                            x,
                            y,
                            null
                    ));
                }
            }
        }

        return candidates;
    }
    private List<Integer> sanitizeTiles(List<Integer> tiles) {
        List<Integer> sanitized = new ArrayList<>();
        if (tiles == null) {
            return sanitized;
        }
        for (Integer tile : tiles) {
            if (tile != null && isValidTileIndex(tile)) {
                sanitized.add(tile);
            }
        }
        return sanitized;
    }

    private List<Integer> filterByAvailability(List<Integer> tiles, Set<Integer> availableSet) {
        List<Integer> filtered = new ArrayList<>();
        for (Integer tile : tiles) {
            if (availableSet.contains(tile)) {
                filtered.add(tile);
            }
        }
        return filtered;
    }

    private boolean isValidTileIndex(int tileIndex) {
        return tileIndex >= 0 && tileIndex < TOTAL_TILES;
    }

    private int toTileIndex(int x, int y) {
        if (x < 0 || y < 0) {
            return -1;
        }
        return (y * GRID_SIZE) + x;
    }

    private Path resolvePreferredModelPath() {
        Path fallbackPath = Path.of(defaultModelPath);
        Path configuredBestPath = null;
        if (bestModelPath != null && !bestModelPath.isBlank()) {
            configuredBestPath = Path.of(bestModelPath.trim());
        }
        Path siblingBestPath = fallbackPath.getParent() == null
                ? Path.of("best_model.json")
                : fallbackPath.getParent().resolve("best_model.json");

        if (configuredBestPath != null && Files.exists(configuredBestPath)) {
            return configuredBestPath;
        }
        if (Files.exists(siblingBestPath)) {
            return siblingBestPath;
        }
        return fallbackPath;
    }

    private long getLastModified(Path path) {
        if (path == null || !Files.exists(path)) {
            return -1L;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    private void refreshActiveModelIfNeeded() {
        Path preferredPath = resolvePreferredModelPath();
        long preferredLastModified = getLastModified(preferredPath);
        PolicyModel current = activeModel;
        Path currentPath = current == null ? null : current.path;

        boolean pathChanged = currentPath == null || !currentPath.equals(preferredPath);
        boolean modelUpdated = preferredLastModified > 0 && preferredLastModified != activeModelLastModified;
        if (!pathChanged && !modelUpdated) {
            return;
        }

        synchronized (this) {
            PolicyModel latestCurrent = activeModel;
            Path latestCurrentPath = latestCurrent == null ? null : latestCurrent.path;
            boolean latestPathChanged = latestCurrentPath == null || !latestCurrentPath.equals(preferredPath);
            boolean latestModelUpdated = preferredLastModified > 0 && preferredLastModified != activeModelLastModified;
            if (!latestPathChanged && !latestModelUpdated) {
                return;
            }
            try {
                if (Files.exists(preferredPath)) {
                    activeModel = loadModel(preferredPath);
                    activeModelLastModified = preferredLastModified;
                    System.out.println("AI Model auto-switched to: " + preferredPath.toAbsolutePath());
                }
            } catch (Exception e) {
                System.err.println("AI Model auto-switch failed: " + e.getMessage());
            }
        }
    }

    private PolicyModel loadModel(Path path) throws IOException {
        if (path == null || !path.toFile().exists()) {
            return PolicyModel.empty(path == null ? Path.of("unknown") : path);
        }

        JsonNode root = objectMapper.readTree(path.toFile());

        // q_policy.json의 최상위 키가 'state_dict'인 경우와 'policy_state_dict'인 경우 모두 대응
        JsonNode weights = root.has("state_dict") ? root.get("state_dict") : root.path("policy_state_dict");
        
        int stateSize = root.path("state_size").asInt(0);
        int actionSize = root.path("action_size").asInt(0);

        boolean hybridModel = weights.has("conv1.weight") || weights.has("policy_head.weight");

        // 만약 actionSize가 명시되어 있지 않다면 가중치 행렬의 마지막 레이어 크기로 추론
        if (actionSize <= 0 && weights.has("net.4.bias")) {
            actionSize = weights.get("net.4.bias").size();
        }
        if (actionSize <= 0 && weights.has("policy_head.bias")) {
            actionSize = weights.get("policy_head.bias").size();
        }

        List<String> mapData = parseMapData(root.path("map_data"));
        List<Integer> playerTiles = parseIntList(root.path("player_tiles"));
        List<Integer> enemyTiles = parseIntList(root.path("enemy_tiles"));

        PolicyModel model = new PolicyModel(path, stateSize, actionSize, mapData, playerTiles, enemyTiles);
        model.hybridModel = hybridModel;

        if (hybridModel) {
            model.policyHeadBias = toVector(weights.path("policy_head.bias"));
            if (model.actionSize <= 0 && model.policyHeadBias.length > 0) {
                model.actionSizeHint = model.policyHeadBias.length;
            }
            return model;
        }

        model.W1 = toMatrix(weights.path("net.0.weight"));
        model.B1 = toVector(weights.path("net.0.bias"));
        model.W2 = toMatrix(weights.path("net.2.weight"));
        model.B2 = toVector(weights.path("net.2.bias"));
        model.W3 = toMatrix(weights.path("net.4.weight"));
        model.B3 = toVector(weights.path("net.4.bias"));

        return model;
    }

    private List<String> parseMapData(JsonNode node) {
        List<String> map = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return map;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                map.add(item.asText(""));
            }
            return map;
        }

        String text = node.asText("");
        if (!text.isBlank()) {
            String[] tokens = text.split(",");
            for (String token : tokens) {
                if (!token.isBlank()) {
                    map.add(token.trim());
                }
            }
        }
        return map;
    }

    private List<String> parseMapData(String mapDataRaw) {
        List<String> map = new ArrayList<>();
        if (mapDataRaw == null || mapDataRaw.isBlank()) {
            return map;
        }
        String[] tokens = mapDataRaw.split(",");
        for (String token : tokens) {
            if (token != null && !token.isBlank()) {
                map.add(token.trim());
            }
        }
        return map;
    }

    private List<Integer> parseIntList(JsonNode node) {
        List<Integer> values = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return values;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                values.add(item.asInt());
            }
        }
        return values;
    }

    private double[][] toMatrix(JsonNode node) {
        if (node == null || !node.isArray() || node.size() == 0) {
            return new double[0][0];
        }

        int rows = node.size();
        int cols = node.get(0).size();
        double[][] matrix = new double[rows][cols];

        for (int i = 0; i < rows; i++) {
            JsonNode row = node.get(i);
            for (int j = 0; j < cols; j++) {
                matrix[i][j] = row.get(j).asDouble();
            }
        }
        return matrix;
    }

    private double[] toVector(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new double[0];
        }

        double[] vector = new double[node.size()];
        for (int i = 0; i < node.size(); i++) {
            vector[i] = node.get(i).asDouble();
        }
        return vector;
    }

    private static class CandidateAction {
        final boolean pass;
        final int actionIndex;
        final String diceType;
        final int x;
        final int y;
        final BattleMessage mergeTarget;

        CandidateAction(
                boolean pass,
                int actionIndex,
                String diceType,
                int x,
                int y,
                BattleMessage mergeTarget
        ) {
            this.pass = pass;
            this.actionIndex = actionIndex;
            this.diceType = diceType;
            this.x = x;
            this.y = y;
            this.mergeTarget = mergeTarget;
        }

        static CandidateAction pass() {
            return new CandidateAction(true, 0, null, -1, -1, null);
        }

        boolean isCompleteTurn() {
            return pass;
        }
    }

    private static class PolicyModel {
        final Path path;
        final int stateSize;
        final int actionSize;

        final List<String> mapData;
        final List<Integer> playerTiles;
        final List<Integer> enemyTiles;

        double[][] W1;
        double[] B1;
        double[][] W2;
        double[] B2;
        double[][] W3;
        double[] B3;
        double[] policyHeadBias;
        boolean hybridModel;
        int actionSizeHint;

        PolicyModel(
                Path path,
                int stateSize,
                int actionSize,
                List<String> mapData,
                List<Integer> playerTiles,
                List<Integer> enemyTiles
        ) {
            this.path = path;
            this.stateSize = stateSize;
            this.actionSize = actionSize;
            this.mapData = mapData == null ? new ArrayList<>() : mapData;
            this.playerTiles = playerTiles == null ? new ArrayList<>() : playerTiles;
            this.enemyTiles = enemyTiles == null ? new ArrayList<>() : enemyTiles;
            this.hybridModel = false;
            this.policyHeadBias = new double[0];
            this.actionSizeHint = actionSize;
        }

        static PolicyModel empty(Path path) {
            return new PolicyModel(path, 0, 0, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        double[] predict(int[] state) {
            int effectiveActionSize = actionSize > 0 ? actionSize : actionSizeHint;

            if (effectiveActionSize <= 0) {
                return new double[0];
            }

            if (hybridModel) {
                if (policyHeadBias == null || policyHeadBias.length == 0) {
                    return new double[0];
                }
                double[] logits = new double[effectiveActionSize];
                int len = Math.min(effectiveActionSize, policyHeadBias.length);
                for (int i = 0; i < len; i++) {
                    logits[i] = policyHeadBias[i];
                }
                return logits;
            }

            if (!isReady()) {
                double[] fallback = new double[effectiveActionSize];
                for (int i = 0; i < fallback.length; i++) {
                    fallback[i] = ThreadLocalRandom.current().nextDouble();
                }
                return fallback;
            }

            double[] input = adjustState(state, stateSize > 0 ? stateSize : state.length);

            double[] l1 = relu(add(matmul(input, W1), B1));
            double[] l2 = relu(add(matmul(l1, W2), B2));
            return add(matmul(l2, W3), B3);
        }

        private boolean isReady() {
            return W1 != null && B1 != null && W2 != null && B2 != null && W3 != null && B3 != null
                    && W1.length > 0 && W2.length > 0 && W3.length > 0;
        }

        private double[] adjustState(int[] state, int expectedSize) {
            double[] input = new double[expectedSize];
            int len = Math.min(state.length, expectedSize);
            for (int i = 0; i < len; i++) {
                input[i] = state[i];
            }
            return input;
        }

        private double[] matmul(double[] vec, double[][] w) {
            if (w.length == 0) {
                return new double[0];
            }

            int outSize = w.length;
            int inSize = w[0].length;
            double[] out = new double[outSize];

            for (int i = 0; i < outSize; i++) {
                double sum = 0.0;
                int bound = Math.min(inSize, vec.length);
                for (int j = 0; j < bound; j++) {
                    sum += vec[j] * w[i][j];
                }
                out[i] = sum;
            }
            return out;
        }

        private double[] add(double[] a, double[] b) {
            if (a.length == 0) {
                return new double[0];
            }

            double[] out = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                double bias = i < b.length ? b[i] : 0.0;
                out[i] = a[i] + bias;
            }
            return out;
        }

        private double[] relu(double[] v) {
            double[] out = new double[v.length];
            for (int i = 0; i < v.length; i++) {
                out[i] = Math.max(0.0, v[i]);
            }
            return out;
        }
    }
}
