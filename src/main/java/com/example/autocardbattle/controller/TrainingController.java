package com.example.autocardbattle.controller;

import com.example.autocardbattle.service.TrainingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/api/training", produces = "application/json")
public class TrainingController {

    private final TrainingService trainingService;

    @Autowired
    public TrainingController(TrainingService trainingService) {
        this.trainingService = trainingService;
    }

    @GetMapping
    public List<Map<String, Object>> getTrainingInfo() {
        return trainingService.listJobs();
    }

    @PostMapping
    public Map<String, Object> createTraining(@RequestBody(required = false) Map<String, Integer> trainingData) throws IOException {
        int episodes = trainingData != null && trainingData.get("episodes") != null ? trainingData.get("episodes") : 2500;
        int logInterval = trainingData != null && trainingData.get("logInterval") != null ? trainingData.get("logInterval") : 100;
        return trainingService.startTraining(episodes, logInterval);
    }

    @GetMapping("/{id}")
    public Map<String, Object> getTrainingJob(@PathVariable String id, @RequestParam(defaultValue = "100") int logLimit) {
        return trainingService.getJob(id, logLimit);
    }
    
    @PostMapping("/{id}/activate")
    public Map<String, Object> activateTrainingModel(@PathVariable String id) throws IOException {
        return trainingService.activateModel(id);
    }
}
