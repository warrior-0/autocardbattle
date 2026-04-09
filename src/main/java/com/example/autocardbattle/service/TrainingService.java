package com.example.autocardbattle.service;

import com.example.autocardbattle.entity.DiceEntity;
import com.example.autocardbattle.repository.DiceRepository;
import com.example.autocardbattle.entity.MapTileEntity;
import com.example.autocardbattle.repository.MapRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class TrainingService {

    private static final int MAX_LOG_LINES = 400;

    private final DiceRepository diceRepository;
    private final MapRepository mapRepository;
    private final ObjectMapper objectMapper;
    private final RlAiService rlAiService;
    private final Map<String, TrainingJob> jobs = new ConcurrentHashMap<>();

    @Autowired
    public TrainingService(DiceRepository diceRepository, MapRepository mapRepository, ObjectMapper objectMapper, RlAiService rlAiService) {
        this.diceRepository = diceRepository;
        this.mapRepository = mapRepository;
        this.objectMapper = objectMapper;
        this.rlAiService = rlAiService;
    }

    /**
     * [추가] 서버 시작 시 미완료된 학습 작업이 있는지 확인하고 자동으로 재개합니다.
     */
    @PostConstruct
    public void init() {
        Path metaDir = Path.of("src", "main", "resources", "python", "trained_models", "meta");
        if (Files.exists(metaDir)) {
            try (var stream = Files.list(metaDir)) {
                stream.filter(path -> path.toString().endsWith(".json")).forEach(path -> {
                    try {
                        Map<String, Object> meta = objectMapper.readValue(path.toFile(), Map.class);
                        String status = (String) meta.get("status");
                        if ("RUNNING".equals(status)) {
                            String jobId = (String) meta.get("jobId");
                            int episodes = (int) meta.get("episodes");
                            int logInterval = (int) meta.get("logInterval");
                            System.out.println("[Auto-Resume] Resuming unfinished job: " + jobId);
                            resumeTraining(jobId, episodes, logInterval);
                        }
                    } catch (IOException e) {
                        System.err.println("[Auto-Resume] Failed to read meta file: " + path);
                    }
                });
            } catch (IOException e) {
                System.err.println("[Auto-Resume] Failed to list meta directory");
            }
        }
    }



    public Map<String, Object> startTraining(int episodes, int logInterval) throws IOException {
        String jobId = UUID.randomUUID().toString();
        return resumeTraining(jobId, episodes, logInterval);
    }

    private Map<String, Object> resumeTraining(String jobId, int episodes, int logInterval) throws IOException {
        Path pythonDir = Path.of("src", "main", "resources", "python");
        // [수] 모든 학습 결과가 하나의 파일에 덮어씌워지도록 q_policy.json을 직접 사용합니다.
        // 이를 통해 GitHub에 중복 파일이 쌓이는 것을 방지하고 최신 모델만 유지합니다.
        Path modelPath = pythonDir.resolve("q_policy.json");
        Path stableModelPath = pythonDir.resolve("q_policy_stable.json");
        Path metaPath = pythonDir.resolve("trained_models").resolve("meta").resolve(jobId + ".json");
        
        Files.createDirectories(modelPath.getParent());
        Files.createDirectories(metaPath.getParent());

        // 메타데이터 저장 (재시작 시 활용)
        Map<String, Object> meta = new HashMap<>();
        meta.put("jobId", jobId);
        meta.put("status", "RUNNING");
        meta.put("episodes", episodes);
        meta.put("logInterval", logInterval);
        objectMapper.writeValue(metaPath.toFile(), meta);

        Path diceCatalogPath = writeDiceCatalog(jobId);
        List<MapTileEntity> allMaps = mapRepository.findAll();
        if (allMaps.isEmpty()) {
            throw new RuntimeException("No map found in DB");
        }

        List<MapTileEntity> selectedMaps = new ArrayList<>(allMaps);
        if (selectedMaps.size() > 10) {
            Collections.shuffle(selectedMaps);
            selectedMaps = new ArrayList<>(selectedMaps.subList(0, 10));
        }

        List<String> mapPoolData = selectedMaps.stream()
                .map(MapTileEntity::getMapData)
                .toList();
        String mapPoolJson = objectMapper.writeValueAsString(mapPoolData);
        
        List<String> command = List.of("nice", "-n", "19", "python3", "ai_trainer.py");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(pythonDir.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("AUTOCARDBATTLE_DICE_CATALOG_FILE", diceCatalogPath.toAbsolutePath().toString());
        builder.environment().put("AUTOCARDBATTLE_MODEL_PATH", modelPath.toAbsolutePath().toString());
        builder.environment().put("AUTOCARDBATTLE_INIT_MODEL_PATH", stableModelPath.toAbsolutePath().toString());
        builder.environment().put("AUTOCARDBATTLE_TRAIN_EPISODES", String.valueOf(episodes));
        builder.environment().put("AUTOCARDBATTLE_TRAIN_LOG_INTERVAL", String.valueOf(logInterval));
        builder.environment().put("AUTOCARDBATTLE_UPDATE_BATCH_EPISODES",
        System.getenv().getOrDefault("AUTOCARDBATTLE_UPDATE_BATCH_EPISODES", "100"));
        builder.environment().put("AUTOCARDBATTLE_MAP_POOL_JSON", mapPoolJson);
        builder.environment().put("AUTOCARDBATTLE_JOB_ID", jobId);

        Process process = builder.start();
        TrainingJob job = new TrainingJob(jobId, process, diceCatalogPath, modelPath, stableModelPath, metaPath, episodes, logInterval);
        jobs.put(jobId, job);
        streamLogs(job);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", jobId);
        response.put("status", job.status);
        response.put("episodes", job.episodes);
        response.put("logInterval", job.logInterval);
        response.put("modelPath", job.modelPath.toString());
        response.put("activeModel", rlAiService.getActiveModelInfo());
        return response;
    }

    public Map<String, Object> getJob(String jobId, int logLimit) {
        TrainingJob job = jobs.get(jobId);
        if (job == null) {
            return Map.of("error", "Training job not found", "jobId", jobId);
        }

        List<String> logs;
        synchronized (job.logs) {
            logs = new ArrayList<>(job.logs);
        }
        int fromIndex = Math.max(0, logs.size() - Math.max(1, logLimit));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", job.id);
        response.put("status", job.status);
        response.put("startedAt", job.startedAt.toString());
        response.put("finishedAt", job.finishedAt == null ? null : job.finishedAt.toString());
        response.put("episodes", job.episodes);
        response.put("logInterval", job.logInterval);
        response.put("exitCode", job.exitCode);
        response.put("modelPath", job.modelPath.toString());
        response.put("logCount", logs.size());
        response.put("logs", logs.subList(fromIndex, logs.size()));
        return response;
    }

    public Map<String, Object> activateModel(String jobId) throws IOException {
        TrainingJob job = jobs.get(jobId);
        if (job == null) {
            return Map.of("error", "Training job not found", "jobId", jobId);
        }
        if (!"COMPLETED".equals(job.status)) {
            return Map.of("error", "Training job is not completed", "jobId", jobId, "status", job.status);
        }
        rlAiService.activateModel(job.modelPath);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", job.id);
        response.put("status", job.status);
        response.put("activatedModel", job.modelPath.toString());
        response.put("activeModel", rlAiService.getActiveModelInfo());
        return response;
    }

    public Map<String, Object> stopJob(String jobId) {
        TrainingJob job = jobs.get(jobId);
        if (job == null) {
            return Map.of("error", "Training job not found", "jobId", jobId);
        }

        if (!job.process.isAlive()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", job.id);
            response.put("status", job.status);
            response.put("message", "Process is not running");
            response.put("exitCode", job.exitCode);
            return response;
        }

        boolean stopped = false;
        job.process.destroy();
        try {
            stopped = job.process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!stopped) {
            job.process.destroyForcibly();
            try {
                stopped = job.process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (stopped) {
            job.status = "STOPPED";
            job.finishedAt = Instant.now();
            if (job.exitCode == null) {
                job.exitCode = 143;
            }
            updateMetaStatus(job, "STOPPED");
            appendLog(job, "Training stopped by API request.");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", job.id);
        response.put("status", job.status);
        response.put("stopped", stopped);
        response.put("exitCode", job.exitCode);
        return response;
    }


    public List<Map<String, Object>> listJobs() {
        return jobs.values().stream()
                .sorted((left, right) -> right.startedAt.compareTo(left.startedAt))
                .map(job -> getJob(job.id, 20))
                .toList();
    }

    private Path writeDiceCatalog(String jobId) throws IOException {
        Path outputDir = Path.of("src", "main", "resources", "python", "training_runs");
        Files.createDirectories(outputDir);
        Path catalogPath = outputDir.resolve("dice_catalog_" + jobId + ".json");

        List<Map<String, Object>> payload = diceRepository.findAll().stream()
                .map(this::toDicePayload)
                .toList();
        objectMapper.writeValue(catalogPath.toFile(), payload);
        return catalogPath;
    }

    private Map<String, Object> toDicePayload(DiceEntity diceEntity) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", diceEntity.getId());
        row.put("diceType", diceEntity.getDiceType());
        row.put("name", diceEntity.getName());
        row.put("hp", diceEntity.getHp());
        row.put("damage", diceEntity.getDamage());
        row.put("range", diceEntity.getRange());
        row.put("aps", diceEntity.getAps());
        row.put("description", diceEntity.getDescription());
        row.put("color", diceEntity.getColor());
        return row;
    }

    private void streamLogs(TrainingJob job) {
        Thread logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(job.process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(job, line);
                }
                int exitCode = job.process.waitFor();
                job.exitCode = exitCode;
                if ("STOPPED".equals(job.status)) {
                    updateMetaStatus(job, "STOPPED");
                    return;
                }
                if (exitCode == 0) {
                    Path normalizedModelPath = job.modelPath.toAbsolutePath().normalize();
                    Path normalizedStablePath = job.stableModelPath.toAbsolutePath().normalize();
                    if (!normalizedModelPath.equals(normalizedStablePath)) {
                        Files.copy(job.modelPath, job.stableModelPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    job.status = "COMPLETED";
                    updateMetaStatus(job, "COMPLETED");
                } else {
                    job.status = "FAILED";
                    updateMetaStatus(job, "FAILED");
                }
            } catch (Exception exception) {
                appendLog(job, "Training stream failed: " + exception.getMessage());
                job.status = "FAILED";
                job.exitCode = -1;
            } finally {
                job.finishedAt = Instant.now();
            }
        }, "training-log-" + job.id);
        logThread.setDaemon(true);
        logThread.start();
    }

    private void updateMetaStatus(TrainingJob job, String status) {
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("jobId", job.id);
            meta.put("status", status);
            meta.put("episodes", job.episodes);
            meta.put("logInterval", job.logInterval);
            objectMapper.writeValue(job.metaPath.toFile(), meta);
        } catch (IOException e) {
            System.err.println("[Meta] Failed to update status: " + e.getMessage());
        }
    }

    private void appendLog(TrainingJob job, String line) {
        synchronized (job.logs) {
            if (job.logs.size() >= MAX_LOG_LINES) {
                job.logs.removeFirst();
            }
            job.logs.addLast(line);
        }
    }

    @PreDestroy
    public void shutdown() {
        jobs.values().forEach(job -> {
            if (job.process.isAlive()) {
                job.process.destroy();
            }
        });
    }

    private static class TrainingJob {
        private final String id;
        private final Process process;
        private final Path diceCatalogPath;
        private final Path modelPath;
        private final Path stableModelPath;
        private final Path metaPath;
        private final Instant startedAt;
        private final int episodes;
        private final int logInterval;
        private final Deque<String> logs = new ArrayDeque<>();
        private volatile String status = "RUNNING";
        private volatile Integer exitCode;
        private volatile Instant finishedAt;

        private TrainingJob(String id, Process process, Path diceCatalogPath, Path modelPath, Path stableModelPath, Path metaPath, int episodes, int logInterval) {
            this.id = id;
            this.process = process;
            this.diceCatalogPath = diceCatalogPath;
            this.modelPath = modelPath;
            this.stableModelPath = stableModelPath;
            this.metaPath = metaPath;
            this.startedAt = Instant.now();
            this.episodes = episodes;
            this.logInterval = logInterval;
        }
    }
}
