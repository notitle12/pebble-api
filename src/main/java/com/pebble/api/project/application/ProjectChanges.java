package com.pebble.api.project.application;

import com.pebble.api.project.domain.ProjectLifecycleStatus;
import com.pebble.api.project.domain.ProjectLinkType;
import com.pebble.api.project.domain.ProjectVisibility;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public record ProjectChanges(Set<String> supplied, String name, String summary, String description,
                             String architectureDescription, String executionInstructions,
                             ProjectLifecycleStatus lifecycleStatus, LocalDate startedOn, LocalDate completedOn,
                             List<Long> techStackIds, List<FeatureInput> features, List<LinkInput> links,
                             ProjectVisibility visibilityStatus, com.fasterxml.jackson.databind.JsonNode document) {
    public ProjectChanges(Set<String> supplied, String name, String summary, String description,
                          String architectureDescription, String executionInstructions,
                          ProjectLifecycleStatus lifecycleStatus, LocalDate startedOn, LocalDate completedOn,
                          List<Long> techStackIds, List<FeatureInput> features, List<LinkInput> links, ProjectVisibility visibilityStatus) {
        this(supplied,name,summary,description,architectureDescription,executionInstructions,lifecycleStatus,startedOn,completedOn,
                techStackIds,features,links,visibilityStatus,null);
    }
    public boolean has(String field) { return supplied.contains(field); }
    public record FeatureInput(String title, String description) { }
    public record LinkInput(ProjectLinkType type, String label, String url, int displayOrder) { }
}
