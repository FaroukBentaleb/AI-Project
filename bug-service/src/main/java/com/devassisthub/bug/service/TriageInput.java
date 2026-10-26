package com.devassisthub.bug.service;

/** The parts of a bug report the model may see. Reporter and assignee are deliberately absent. */
public record TriageInput(String title, String description, String stepsToReproduce, String expectedResult,
                          String actualResult, String stackTrace, String environment, String appVersion) {
}
