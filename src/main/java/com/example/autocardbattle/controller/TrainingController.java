package com.example.autocardbattle.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/training")
public class TrainingController {

    @GetMapping
    public String getTrainingInfo() {
        return "Training information retrieved successfully!";
    }

    @PostMapping
    public String createTraining(@RequestBody String trainingData) {
        return "Training created with data: " + trainingData;
    }

    @PutMapping("/{id}")
    public String updateTraining(@PathVariable String id, @RequestBody String trainingData) {
        return "Training with ID " + id + " updated with data: " + trainingData;
    }

    @DeleteMapping("/{id}")
    public String deleteTraining(@PathVariable String id) {
        return "Training with ID " + id + " deleted successfully!";
    }
}