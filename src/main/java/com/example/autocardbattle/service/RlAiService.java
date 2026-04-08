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
            model.conv1W = toTensor4(weights.path("conv1.weight"));
            model.conv1B = toVector(weights.path("conv1.bias"));
            model.conv2W = toTensor4(weights.path("conv2.weight"));
            model.conv2B = toVector(weights.path("conv2.bias"));
            model.spatialFcW = toMatrix(weights.path("spatial_fc.weight"));
            model.spatialFcB = toVector(weights.path("spatial_fc.bias"));
            model.nonFc1W = toMatrix(weights.path("non_fc1.weight"));
            model.nonFc1B = toVector(weights.path("non_fc1.bias"));
            model.nonFc2W = toMatrix(weights.path("non_fc2.weight"));
            model.nonFc2B = toVector(weights.path("non_fc2.bias"));
            model.fuseW = toMatrix(weights.path("fuse.weight"));
            model.fuseB = toVector(weights.path("fuse.bias"));
            model.policyW = toMatrix(weights.path("policy_head.weight"));
            model.policyB = toVector(weights.path("policy_head.bias"));
            model.actionSizeHint = model.policyB.length > 0 ? model.policyB.length : actionSize;
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

    private double[][][][] toTensor4(JsonNode node) {
        if (node == null || !node.isArray() || node.size() == 0) {
            return new double[0][][][];
        }
        int d0 = node.size();
        int d1 = node.get(0).size();
        int d2 = node.get(0).get(0).size();
        int d3 = node.get(0).get(0).get(0).size();
        double[][][][] out = new double[d0][d1][d2][d3];
        for (int i = 0; i < d0; i++) {
            JsonNode n1 = node.get(i);
            for (int j = 0; j < d1; j++) {
                JsonNode n2 = n1.get(j);
                for (int k = 0; k < d2; k++) {
                    JsonNode n3 = n2.get(k);
                    for (int m = 0; m < d3; m++) {
                        out[i][j][k][m] = n3.get(m).asDouble();
                    }
                }
            }
        }
        return out;
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
        double[][][][] conv1W;
        double[] conv1B;
        double[][][][] conv2W;
        double[] conv2B;
        double[][] spatialFcW;
        double[] spatialFcB;
        double[][] nonFc1W;
        double[] nonFc1B;
        double[][] nonFc2W;
        double[] nonFc2B;
        double[][] fuseW;
        double[] fuseB;
        double[][] policyW;
        double[] policyB;
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
                if (!isHybridReady()) {
                    return new double[0];
                }
                return predictHybrid(state, effectiveActionSize);
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

        private boolean isHybridReady() {
            return conv1W != null && conv1W.length > 0
                    && conv2W != null && conv2W.length > 0
                    && spatialFcW != null && spatialFcW.length > 0
                    && nonFc1W != null && nonFc1W.length > 0
                    && nonFc2W != null && nonFc2W.length > 0
                    && fuseW != null && fuseW.length > 0
                    && policyW != null && policyW.length > 0;
        }

        private double[] predictHybrid(int[] state, int effectiveActionSize) {
            final int commonSize = 4;
            final int spatialChannels = 11;
            final int tileCount = 64;
            final int nonSpatialSize = 6;

            int expected = commonSize + (spatialChannels * tileCount) + nonSpatialSize;
            double[] in = adjustState(state, stateSize > 0 ? stateSize : expected);
            if (in.length < expected) {
                in = adjustState(state, expected);
            }

            double[] common = new double[commonSize];
            System.arraycopy(in, 0, common, 0, commonSize);

            double[][][] spatial = new double[spatialChannels][8][8];
            int spatialStart = commonSize;
            for (int c = 0; c < spatialChannels; c++) {
                for (int idx = 0; idx < tileCount; idx++) {
                    int y = idx / 8;
                    int x = idx % 8;
                    spatial[c][y][x] = in[spatialStart + (c * tileCount) + idx];
                }
            }

            double[] handAndResult = new double[nonSpatialSize];
            int hrStart = commonSize + (spatialChannels * tileCount);
            System.arraycopy(in, hrStart, handAndResult, 0, nonSpatialSize);

            double[][][] spatialAug = new double[12][8][8];
            for (int c = 0; c < spatialChannels; c++) {
                for (int y = 0; y < 8; y++) {
                    System.arraycopy(spatial[c][y], 0, spatialAug[c][y], 0, 8);
                }
            }
            double minDist = minEnemyDistanceChannel(spatial);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    spatialAug[11][y][x] = minDist;
                }
            }

            double[][][] c1 = conv2dSame(spatialAug, conv1W, conv1B, 1);
            reluInPlace(c1);
            double[][][] c2 = conv2dSame(c1, conv2W, conv2B, 2);
            reluInPlace(c2);

            double[] pooled = globalAveragePool(c2);
            double[] zSp = add(matmul(spatialFcW, pooled), spatialFcB);
            reluInPlace(zSp);

            double[] nonIn = new double[commonSize + nonSpatialSize];
            System.arraycopy(common, 0, nonIn, 0, commonSize);
            System.arraycopy(handAndResult, 0, nonIn, commonSize, nonSpatialSize);
            double[] n1 = add(matmul(nonFc1W, nonIn), nonFc1B);
            reluInPlace(n1);
            double[] n2 = add(matmul(nonFc2W, n1), nonFc2B);
            reluInPlace(n2);

            double[] fused = new double[zSp.length + n2.length];
            System.arraycopy(zSp, 0, fused, 0, zSp.length);
            System.arraycopy(n2, 0, fused, zSp.length, n2.length);

            double[] zF = add(matmul(fuseW, fused), fuseB);
            reluInPlace(zF);

            double[] logitsRaw = add(matmul(policyW, zF), policyB);
            double[] logits = new double[effectiveActionSize];
            int len = Math.min(effectiveActionSize, logitsRaw.length);
            System.arraycopy(logitsRaw, 0, logits, 0, len);
            return logits;
        }

        private double[] matmul(double[][] w, double[] x) {
            if (w == null || w.length == 0) {
                return new double[0];
            }
            double[] out = new double[w.length];
            for (int i = 0; i < w.length; i++) {
                double sum = 0.0;
                int bound = Math.min(w[i].length, x.length);
                for (int j = 0; j < bound; j++) {
                    sum += w[i][j] * x[j];
                }
                out[i] = sum;
            }
            return out;
        }

        private double[] add(double[] a, double[] b) {
            int n = a.length;
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                out[i] = a[i] + (i < b.length ? b[i] : 0.0);
            }
            return out;
        }

        private void reluInPlace(double[] v) {
            for (int i = 0; i < v.length; i++) {
                if (v[i] < 0.0) v[i] = 0.0;
            }
        }

        private void reluInPlace(double[][][] t) {
            for (int c = 0; c < t.length; c++) {
                for (int y = 0; y < t[c].length; y++) {
                    for (int x = 0; x < t[c][y].length; x++) {
                        if (t[c][y][x] < 0.0) t[c][y][x] = 0.0;
                    }
                }
            }
        }

        private double[][][] conv2dSame(double[][][] x, double[][][][] w, double[] b, int dilation) {
            int outCh = w.length;
            int inCh = x.length;
            int k = w[0][0].length;
            int h = x[0].length;
            int wid = x[0][0].length;
            int pad = (k / 2) * dilation;

            double[][][] out = new double[outCh][h][wid];
            for (int oc = 0; oc < outCh; oc++) {
                for (int i = 0; i < h; i++) {
                    for (int j = 0; j < wid; j++) {
                        double sum = oc < b.length ? b[oc] : 0.0;
                        for (int ic = 0; ic < inCh; ic++) {
                            for (int ki = 0; ki < k; ki++) {
                                for (int kj = 0; kj < k; kj++) {
                                    int ii = i + (ki * dilation) - pad;
                                    int jj = j + (kj * dilation) - pad;
                                    if (ii < 0 || ii >= h || jj < 0 || jj >= wid) {
                                        continue;
                                    }
                                    sum += x[ic][ii][jj] * w[oc][ic][ki][kj];
                                }
                            }
                        }
                        out[oc][i][j] = sum;
                    }
                }
            }
            return out;
        }

        private double[] globalAveragePool(double[][][] x) {
            double[] out = new double[x.length];
            double denom = x[0].length * x[0][0].length;
            for (int c = 0; c < x.length; c++) {
                double sum = 0.0;
                for (int y = 0; y < x[c].length; y++) {
                    for (int xx = 0; xx < x[c][y].length; xx++) {
                        sum += x[c][y][xx];
                    }
                }
                out[c] = sum / Math.max(1.0, denom);
            }
            return out;
        }

        private double minEnemyDistanceChannel(double[][][] spatial) {
            List<int[]> own = new ArrayList<>();
            List<int[]> opp = new ArrayList<>();
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    boolean ownPresent = false;
                    boolean oppPresent = false;
                    for (int c = 1; c <= 5; c++) {
                        if (Math.abs(spatial[c][y][x]) > 1e-6) {
                            ownPresent = true;
                            break;
                        }
                    }
                    for (int c = 6; c <= 10; c++) {
                        if (Math.abs(spatial[c][y][x]) > 1e-6) {
                            oppPresent = true;
                            break;
                        }
                    }
                    if (ownPresent) own.add(new int[]{y, x});
                    if (oppPresent) opp.add(new int[]{y, x});
                }
            }
            if (own.isEmpty() || opp.isEmpty()) {
                return 1.0;
            }
            int minDist = Integer.MAX_VALUE;
            for (int[] a : own) {
                for (int[] b : opp) {
                    int d = Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
                    if (d < minDist) {
                        minDist = d;
                    }
                }
            }
            return minDist / 14.0;
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

        private double[] relu(double[] v) {
            double[] out = new double[v.length];
            for (int i = 0; i < v.length; i++) {
                out[i] = Math.max(0.0, v[i]);
            }
            return out;
        }
    }
}
